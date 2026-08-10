#include "filament_chess_core.h"

#include <backend/PixelBufferDescriptor.h>
#include <filament/Camera.h>
#include <filament/Color.h>
#include <filament/ColorGrading.h>
#include <filament/Engine.h>
#include <filament/IndirectLight.h>
#include <filament/LightManager.h>
#include <filament/Material.h>
#include <filament/MaterialInstance.h>
#include <filament/RenderableManager.h>
#include <filament/Renderer.h>
#include <filament/Scene.h>
#include <filament/Skybox.h>
#include <filament/SwapChain.h>
#include <filament/Texture.h>
#include <filament/TransformManager.h>
#include <filament/View.h>
#include <filament/Viewport.h>

#include <gltfio/AssetLoader.h>
#include <gltfio/FilamentAsset.h>
#include <gltfio/FilamentInstance.h>
#include <gltfio/MaterialProvider.h>
#include <gltfio/ResourceLoader.h>
#include <gltfio/TextureProvider.h>
#include <gltfio/materials/uberarchive.h>

#include <image/Ktx1Bundle.h>
#include <ktxreader/Ktx1Reader.h>

#include <math/mat4.h>
#include <math/vec3.h>

#include <utils/EntityManager.h>
#include <utils/NameComponentManager.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

using namespace filament;
using namespace filament::math;
using utils::Entity;

namespace chess3d {
namespace {

static constexpr float kModelScale = 0.5f;
static constexpr int kMaxPieces = 32;
// Keep in sync with ChessSetConventions.MAX_HIGHLIGHTS in commonMain.
static constexpr int kMaxHighlights = 4;
// Lifts the highlight quad off the tile just enough to beat z-fighting.
static constexpr float kHighlightLiftY = 0.005f;
static constexpr int kInstanceCount = kMaxPieces + kMaxHighlights + 1;
static constexpr int kInitialWidth = 1;
static constexpr int kInitialHeight = 1;
static constexpr double kPi = 3.14159265358979323846;

static const char* kMeshForKind[6] = { "king", "queen", "rook", "bishop", "knight", "pawn" };

struct PieceWire {
    int kind = 0;
    int color = 0;
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
    float rotY = 0.0f;
};

struct HighlightWire {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
};

std::vector<std::string> split(const std::string& s, char delim) {
    std::vector<std::string> out;
    if (s.empty()) return out;
    size_t i = 0;
    const size_t n = s.size();
    while (i <= n) {
        size_t j = s.find(delim, i);
        if (j == std::string::npos) j = n;
        out.push_back(s.substr(i, j - i));
        i = j + 1;
    }
    return out;
}

std::vector<PieceWire> parseScenePieces(const std::string& encoded) {
    std::vector<PieceWire> out;
    for (const std::string& record : split(encoded, ';')) {
        std::vector<std::string> fields = split(record, ',');
        if (fields.size() < 6) continue;
        PieceWire p;
        p.kind = std::atoi(fields[0].c_str());
        p.color = std::atoi(fields[1].c_str());
        p.x = std::strtof(fields[2].c_str(), nullptr);
        p.y = std::strtof(fields[3].c_str(), nullptr);
        p.z = std::strtof(fields[4].c_str(), nullptr);
        p.rotY = std::strtof(fields[5].c_str(), nullptr);
        out.push_back(p);
    }
    return out;
}

std::vector<HighlightWire> parseSceneHighlights(const std::string& encoded) {
    std::vector<HighlightWire> out;
    for (const std::string& record : split(encoded, ';')) {
        std::vector<std::string> fields = split(record, ',');
        if (fields.size() < 3) continue;
        HighlightWire h;
        h.x = std::strtof(fields[0].c_str(), nullptr);
        h.y = std::strtof(fields[1].c_str(), nullptr);
        h.z = std::strtof(fields[2].c_str(), nullptr);
        out.push_back(h);
    }
    return out;
}

struct ReadbackContext {
    explicit ReadbackContext(size_t size) : bytes(size) {}

    std::mutex mutex;
    std::condition_variable cv;
    std::vector<uint8_t> bytes;
    bool done = false;
    bool abandoned = false;
};

void readbackCallback(void*, size_t, void* user) {
    auto* ctx = static_cast<ReadbackContext*>(user);
    bool shouldDelete = false;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->done = true;
        shouldDelete = ctx->abandoned;
    }
    ctx->cv.notify_one();
    if (shouldDelete) {
        delete ctx;
    }
}

} // namespace

struct FilamentChessCore::Impl {
    Engine* engine = nullptr;
    SwapChain* swapChain = nullptr;
    Renderer* renderer = nullptr;
    View* view = nullptr;
    Scene* scene = nullptr;
    Camera* camera = nullptr;
    Entity cameraEntity;
    Entity sunlight;
    Entity filllight;
    ColorGrading* colorGrading = nullptr;

    IndirectLight* ibl = nullptr;
    Skybox* skybox = nullptr;
    Texture* iblTexture = nullptr;
    Texture* skyboxTexture = nullptr;

    gltfio::AssetLoader* assetLoader = nullptr;
    gltfio::ResourceLoader* resourceLoader = nullptr;
    gltfio::MaterialProvider* materialProvider = nullptr;
    gltfio::TextureProvider* stbProvider = nullptr;
    gltfio::TextureProvider* ktxProvider = nullptr;
    utils::NameComponentManager* names = nullptr;
    gltfio::FilamentAsset* asset = nullptr;
    std::vector<gltfio::FilamentInstance*> instances;

    int width = kInitialWidth;
    int height = kInitialHeight;
    float aspect = 1.0f;
    bool ready = false;
    std::string error;

    Impl(const uint8_t* glb, int glbSize, const uint8_t* iblBytes, int iblSize,
            const uint8_t* skyboxBytes, int skyboxSize)
        : instances(kInstanceCount, nullptr) {
        if (!glb || glbSize <= 0 || !iblBytes || iblSize <= 0 || !skyboxBytes || skyboxSize <= 0) {
            error = "Desktop Filament renderer received empty GLB or KTX assets";
            return;
        }

#if defined(__APPLE__)
        engine = Engine::create(Engine::Backend::METAL);
#else
        engine = Engine::create(Engine::Backend::OPENGL);
#endif
        if (!engine) {
            error = "Filament Engine::create failed";
            return;
        }

        swapChain = engine->createSwapChain(static_cast<uint32_t>(width), static_cast<uint32_t>(height), SwapChain::CONFIG_READABLE);
        renderer = engine->createRenderer();
        scene = engine->createScene();
        view = engine->createView();
        cameraEntity = utils::EntityManager::get().create();
        camera = engine->createCamera(cameraEntity);

        view->setScene(scene);
        view->setCamera(camera);
        view->setViewport({0, 0, static_cast<uint32_t>(width), static_cast<uint32_t>(height)});
        view->setBlendMode(View::BlendMode::OPAQUE);

        colorGrading = ColorGrading::Builder()
            .toneMapping(ColorGrading::ToneMapping::ACES_LEGACY)
            .build(*engine);
        view->setColorGrading(colorGrading);

        setupLighting(iblBytes, iblSize, skyboxBytes, skyboxSize);
        if (!loadGlb(glb, glbSize)) {
            if (error.empty()) error = "Filament glTF asset load failed";
            shutdown();
            return;
        }
        ready = true;
    }

    ~Impl() {
        shutdown();
    }

    void setupLighting(const uint8_t* iblBytes, int iblSize, const uint8_t* skyboxBytes, int skyboxSize) {
        static constexpr float kExposureComp = 2.2222f;
        static constexpr float kMainLux = 11500.0f * kExposureComp;
        static constexpr float kFillLux = 3450.0f * kExposureComp;
        static constexpr float kIblLux = 11500.0f * kExposureComp;

        auto* iblBundle = new image::Ktx1Bundle(iblBytes, static_cast<uint32_t>(iblSize));
        float3 harmonics[9];
        bool hasSh = iblBundle->getSphericalHarmonics(harmonics);
        iblTexture = ktxreader::Ktx1Reader::createTexture(
            engine, *iblBundle, false,
            [](void* userdata) { delete static_cast<image::Ktx1Bundle*>(userdata); },
            iblBundle);
        if (iblTexture) {
            IndirectLight::Builder iblBuilder;
            iblBuilder.reflections(iblTexture);
            if (hasSh) iblBuilder.irradiance(3, harmonics);
            iblBuilder.intensity(kIblLux);
            ibl = iblBuilder.build(*engine);
            scene->setIndirectLight(ibl);
        }

        auto* skyboxBundle = new image::Ktx1Bundle(skyboxBytes, static_cast<uint32_t>(skyboxSize));
        skyboxTexture = ktxreader::Ktx1Reader::createTexture(
            engine, *skyboxBundle, false,
            [](void* userdata) { delete static_cast<image::Ktx1Bundle*>(userdata); },
            skyboxBundle);
        if (skyboxTexture) {
            skybox = Skybox::Builder().environment(skyboxTexture).showSun(false).build(*engine);
            scene->setSkybox(skybox);
        }

        const LinearColor neutral = Color::cct(6500.0f);

        sunlight = utils::EntityManager::get().create();
        LightManager::Builder(LightManager::Type::DIRECTIONAL)
            .color(neutral)
            .intensity(kMainLux)
            .direction({0.0f, -1.0f, 0.0f})
            .castShadows(true)
            .build(*engine, sunlight);
        scene->addEntity(sunlight);

        filllight = utils::EntityManager::get().create();
        LightManager::Builder(LightManager::Type::DIRECTIONAL)
            .color(neutral)
            .intensity(kFillLux)
            .direction({0.5f, -0.5f, 0.5f})
            .castShadows(false)
            .build(*engine, filllight);
        scene->addEntity(filllight);
    }

    bool loadGlb(const uint8_t* glb, int glbSize) {
        names = new utils::NameComponentManager(utils::EntityManager::get());
        materialProvider = gltfio::createUbershaderProvider(
            engine, UBERARCHIVE_DEFAULT_DATA, UBERARCHIVE_DEFAULT_SIZE);
        assetLoader = gltfio::AssetLoader::create({engine, materialProvider, names});

        std::vector<gltfio::FilamentInstance*> loaded(kInstanceCount, nullptr);
        asset = assetLoader->createInstancedAsset(
            glb, static_cast<uint32_t>(glbSize), loaded.data(), kInstanceCount);
        if (!asset) {
            error = "gltfio createInstancedAsset failed";
            return false;
        }
        instances = loaded;

        gltfio::ResourceConfiguration cfg;
        cfg.engine = engine;
        cfg.gltfPath = "";
        cfg.normalizeSkinningWeights = true;
        resourceLoader = new gltfio::ResourceLoader(cfg);
        stbProvider = gltfio::createStbProvider(engine);
        ktxProvider = gltfio::createKtx2Provider(engine);
        resourceLoader->addTextureProvider("image/png", stbProvider);
        resourceLoader->addTextureProvider("image/jpeg", stbProvider);
        resourceLoader->addTextureProvider("image/ktx2", ktxProvider);
        resourceLoader->loadResources(asset);
        asset->releaseSourceData();
        resourceLoader->evictResourceData();

        configureInstanceVisibility();
        return true;
    }

    void configureInstanceVisibility() {
        gltfio::FilamentInstance* board = instances[0];
        if (board) {
            forEachRenderable(board, [this](Entity e, const char* name) {
                bool isTemplate = false;
                for (const char* pieceName : kMeshForKind) {
                    if (std::strcmp(name, pieceName) == 0) {
                        isTemplate = true;
                        break;
                    }
                }
                bool hidden = isTemplate || std::strcmp(name, "Plane") == 0
                        || std::strcmp(name, "Highlight") == 0;
                if (hidden) scene->remove(e); else scene->addEntity(e);
            });
            setInstanceTransform(board, {0.0f, 0.0f, 0.0f}, 0.0f, kModelScale);
        }

        for (int i = 1; i < kInstanceCount; ++i) {
            gltfio::FilamentInstance* inst = instances[i];
            if (!inst) continue;
            forEachRenderable(inst, [this](Entity e, const char*) { scene->remove(e); });
        }
    }

    template <typename F>
    void forEachRenderable(gltfio::FilamentInstance* inst, F block) {
        auto& rm = engine->getRenderableManager();
        const Entity* entities = inst->getEntities();
        size_t count = inst->getEntityCount();
        for (size_t i = 0; i < count; ++i) {
            Entity e = entities[i];
            if (!rm.hasComponent(e)) continue;
            const char* name = asset->getName(e);
            block(e, name ? name : "");
        }
    }

    MaterialInstance* materialNamed(const char* wanted, gltfio::FilamentInstance* inst) {
        size_t count = inst->getMaterialInstanceCount();
        MaterialInstance* const* materials = inst->getMaterialInstances();
        for (size_t i = 0; i < count; ++i) {
            const char* name = materials[i]->getName();
            if (name && std::strcmp(name, wanted) == 0) return materials[i];
        }
        return nullptr;
    }

    void setInstanceTransform(gltfio::FilamentInstance* inst, float3 pos, float rotYDeg, float scale) {
        auto& tm = engine->getTransformManager();
        Entity root = inst->getRoot();
        auto ti = tm.getInstance(root);
        if (!ti) return;
        float r = rotYDeg * static_cast<float>(kPi) / 180.0f;
        mat4f transform = mat4f::translation(pos)
            * mat4f::rotation(r, float3{0.0f, 1.0f, 0.0f})
            * mat4f::scaling(float3{scale, scale, scale});
        tm.setTransform(ti, transform);
    }

    void resize(int newWidth, int newHeight) {
        if (!engine || newWidth <= 0 || newHeight <= 0) return;
        if (newWidth == width && newHeight == height && swapChain) return;
        width = std::max(1, newWidth);
        height = std::max(1, newHeight);
        aspect = static_cast<float>(width) / static_cast<float>(height);

        if (swapChain) {
            engine->destroy(swapChain);
            swapChain = nullptr;
            engine->flushAndWait();
        }
        swapChain = engine->createSwapChain(static_cast<uint32_t>(width), static_cast<uint32_t>(height), SwapChain::CONFIG_READABLE);
        if (view) {
            view->setViewport({0, 0, static_cast<uint32_t>(width), static_cast<uint32_t>(height)});
        }
    }

    void setSceneEncoded(const std::string& encoded) {
        if (!ready) return;
        
        std::string piecesStr = encoded;
        std::string highlightsStr = "";
        size_t pipePos = encoded.find('|');
        if (pipePos != std::string::npos) {
            piecesStr = encoded.substr(0, pipePos);
            highlightsStr = encoded.substr(pipePos + 1);
        }
        
        std::vector<PieceWire> pieces = parseScenePieces(piecesStr);
        std::vector<HighlightWire> highlights = parseSceneHighlights(highlightsStr);

        for (int slot = 0; slot < kMaxPieces; ++slot) {
            gltfio::FilamentInstance* inst = instances[slot + 1];
            if (!inst) continue;

            if (slot >= static_cast<int>(pieces.size())) {
                forEachRenderable(inst, [this](Entity e, const char*) { scene->remove(e); });
                continue;
            }

            const PieceWire& piece = pieces[slot];
            const char* meshName = (piece.kind >= 0 && piece.kind < 6) ? kMeshForKind[piece.kind] : "";
            const char* materialName = (piece.color == 0) ? "white" : "black";
            MaterialInstance* targetMaterial = materialNamed(materialName, inst);

            forEachRenderable(inst, [this, meshName, targetMaterial](Entity e, const char* name) {
                bool show = std::strcmp(name, meshName) == 0;
                if (show) {
                    scene->addEntity(e);
                    if (targetMaterial) {
                        auto& rm = engine->getRenderableManager();
                        auto ri = rm.getInstance(e);
                        if (ri) {
                            size_t prims = rm.getPrimitiveCount(ri);
                            for (size_t pr = 0; pr < prims; ++pr) {
                                rm.setMaterialInstanceAt(ri, pr, targetMaterial);
                            }
                        }
                    }
                } else {
                    scene->remove(e);
                }
            });

            setInstanceTransform(inst, float3{piece.x, piece.y, piece.z}, piece.rotY, kModelScale);
        }
        
        for (int slot = 0; slot < kMaxHighlights; ++slot) {
            gltfio::FilamentInstance* inst = instances[kMaxPieces + 1 + slot];
            if (!inst) continue;

            if (slot >= static_cast<int>(highlights.size())) {
                forEachRenderable(inst, [this](Entity e, const char*) { scene->remove(e); });
                continue;
            }

            const HighlightWire& h = highlights[slot];

            // No material binding: the Plane mesh carries its own `highlight` material (alphaMode
            // BLEND) in chess.glb. Tinting one of the OPAQUE glTF materials at runtime cannot work —
            // gltfio selects the ubershader blending variant from alphaMode when the asset loads.
            forEachRenderable(inst, [this](Entity e, const char* name) {
                if (std::strcmp(name, "Highlight") == 0) {
                    scene->addEntity(e);
                } else {
                    scene->remove(e);
                }
            });

            // The Plane node's baked local translation was zeroed in chess.glb, so the square centre
            // applies directly; kHighlightLiftY clears the tile to avoid z-fighting.
            setInstanceTransform(inst, float3{h.x, kHighlightLiftY, h.z}, 0.0f, kModelScale);
        }
    }

    void setCameraEncoded(const std::string& encoded) {
        if (!camera) return;
        std::vector<std::string> f = split(encoded, ',');
        if (f.size() < 11) return;

        float px = std::strtof(f[0].c_str(), nullptr);
        float py = std::strtof(f[1].c_str(), nullptr);
        float pz = std::strtof(f[2].c_str(), nullptr);
        float tx = std::strtof(f[3].c_str(), nullptr);
        float ty = std::strtof(f[4].c_str(), nullptr);
        float tz = std::strtof(f[5].c_str(), nullptr);
        float ux = std::strtof(f[6].c_str(), nullptr);
        float uy = std::strtof(f[7].c_str(), nullptr);
        float uz = std::strtof(f[8].c_str(), nullptr);
        float fovY = std::strtof(f[9].c_str(), nullptr);
        aspect = std::strtof(f[10].c_str(), nullptr);
        if (aspect <= 0.0f) aspect = static_cast<float>(width) / static_cast<float>(height);

        camera->lookAt({px, py, pz}, {tx, ty, tz}, {ux, uy, uz});

        double fov = fovY;
        if (aspect < 1.0f) {
            double tanHalfFovX = std::tan((60.0 * kPi / 180.0) / 2.0);
            fov = 2.0 * std::atan(tanHalfFovX / static_cast<double>(aspect)) * 180.0 / kPi;
        }
        camera->setProjection(fov, aspect, 0.1, 100.0, Camera::Fov::VERTICAL);
    }

    RenderResult render() {
        RenderResult result;
        result.width = width;
        result.height = height;

        if (!ready || !engine || !renderer || !swapChain || !view) {
            result.error = error.empty() ? "Desktop Filament renderer is not ready" : error;
            return result;
        }

        const size_t byteCount = static_cast<size_t>(width) * static_cast<size_t>(height) * 4u;
        auto* ctx = new ReadbackContext(byteCount);

        if (renderer->beginFrame(swapChain)) {
            renderer->render(view);
            backend::PixelBufferDescriptor pixels(
                ctx->bytes.data(), ctx->bytes.size(),
                backend::PixelDataFormat::RGBA, backend::PixelDataType::UBYTE,
                readbackCallback, ctx);
            pixels.alignment = 1;
            pixels.stride = static_cast<uint32_t>(width);
            renderer->readPixels(0, 0, static_cast<uint32_t>(width), static_cast<uint32_t>(height), std::move(pixels));
            renderer->endFrame();
            engine->flushAndWait();
        } else {
            delete ctx;
            result.error = "Filament renderer refused beginFrame";
            return result;
        }

        std::unique_lock<std::mutex> lock(ctx->mutex);
        if (!ctx->cv.wait_for(lock, std::chrono::seconds(5), [ctx] { return ctx->done; })) {
            ctx->abandoned = true;
            result.error = "Timed out waiting for Filament readPixels";
            return result;
        }
        // Filament's Metal backend reads pixels back top-left origin, matching the Skia
        // ImageBitmap layout on the Kotlin side, so no row flip is needed here.
        result.rgba = std::move(ctx->bytes);
        lock.unlock();
        delete ctx;
        return result;
    }

    void shutdown() {
        if (!engine) return;
        ready = false;

        if (resourceLoader) { delete resourceLoader; resourceLoader = nullptr; }
        if (asset) { assetLoader->destroyAsset(asset); asset = nullptr; }
        if (stbProvider) { delete stbProvider; stbProvider = nullptr; }
        if (ktxProvider) { delete ktxProvider; ktxProvider = nullptr; }
        if (materialProvider) { materialProvider->destroyMaterials(); delete materialProvider; materialProvider = nullptr; }
        if (assetLoader) { gltfio::AssetLoader::destroy(&assetLoader); assetLoader = nullptr; }
        if (names) { delete names; names = nullptr; }

        if (colorGrading) { engine->destroy(colorGrading); colorGrading = nullptr; }
        if (ibl) { engine->destroy(ibl); ibl = nullptr; }
        if (skybox) { engine->destroy(skybox); skybox = nullptr; }
        if (iblTexture) { engine->destroy(iblTexture); iblTexture = nullptr; }
        if (skyboxTexture) { engine->destroy(skyboxTexture); skyboxTexture = nullptr; }
        if (sunlight) { engine->destroy(sunlight); utils::EntityManager::get().destroy(sunlight); sunlight.clear(); }
        if (filllight) { engine->destroy(filllight); utils::EntityManager::get().destroy(filllight); filllight.clear(); }

        if (camera) { engine->destroyCameraComponent(cameraEntity); camera = nullptr; }
        if (cameraEntity) { utils::EntityManager::get().destroy(cameraEntity); cameraEntity.clear(); }
        if (view) { engine->destroy(view); view = nullptr; }
        if (scene) { engine->destroy(scene); scene = nullptr; }
        if (renderer) { engine->destroy(renderer); renderer = nullptr; }
        if (swapChain) { engine->destroy(swapChain); swapChain = nullptr; }

        Engine::destroy(&engine);
        engine = nullptr;
        instances.clear();
    }
};

FilamentChessCore::FilamentChessCore(const uint8_t* glb, int glbSize,
        const uint8_t* ibl, int iblSize,
        const uint8_t* skybox, int skyboxSize)
    : impl(new Impl(glb, glbSize, ibl, iblSize, skybox, skyboxSize)) {
}

FilamentChessCore::~FilamentChessCore() {
    delete impl;
}

bool FilamentChessCore::valid() const {
    return impl && impl->ready && impl->engine;
}

const std::string& FilamentChessCore::lastError() const {
    static const std::string empty;
    return impl ? impl->error : empty;
}

void FilamentChessCore::resize(int width, int height) {
    if (impl) impl->resize(width, height);
}

void FilamentChessCore::setScene(const std::string& encoded) {
    if (impl) impl->setSceneEncoded(encoded);
}

void FilamentChessCore::setCamera(const std::string& encoded) {
    if (impl) impl->setCameraEncoded(encoded);
}

RenderResult FilamentChessCore::render() {
    if (!impl) {
        return RenderResult{0, 0, {}, "Desktop Filament renderer has no implementation"};
    }
    return impl->render();
}

} // namespace chess3d

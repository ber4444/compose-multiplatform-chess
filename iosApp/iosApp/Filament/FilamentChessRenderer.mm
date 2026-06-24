// Issue #54 — Filament Metal renderer for the iOS 3D chess board (Obj-C++).
//
// Compiles against the Filament iOS xcframework fetched by tools/fetch_filament_ios.sh. The pipeline
// mirrors the Android SceneView/Filament backend (AndroidBoard3D.kt) and follows Filament's own iOS
// samples (`hellopbr`, `gltf-viewer`, `image_based_lighting`).

#import "FilamentChessRenderer.h"

#import <filament/Engine.h>
#import <filament/SwapChain.h>
#import <filament/Renderer.h>
#import <filament/View.h>
#import <filament/Scene.h>
#import <filament/Camera.h>
#import <filament/Viewport.h>
#import <filament/IndirectLight.h>
#import <filament/Skybox.h>
#import <filament/Texture.h>
#import <filament/LightManager.h>
#import <filament/RenderableManager.h>
#import <filament/TransformManager.h>
#import <filament/Material.h>
#import <filament/MaterialInstance.h>
#import <filament/ColorGrading.h>

#import <gltfio/AssetLoader.h>
#import <gltfio/FilamentAsset.h>
#import <gltfio/FilamentInstance.h>
#import <gltfio/ResourceLoader.h>
#import <gltfio/TextureProvider.h>
#import <gltfio/MaterialProvider.h>
#import <gltfio/materials/uberarchive.h>

#import <ktxreader/Ktx1Reader.h>
#import <image/Ktx1Bundle.h>

#import <utils/EntityManager.h>
#import <utils/NameComponentManager.h>

#import <math/mat4.h>
#import <math/vec3.h>

#import <string>
#import <vector>
#import <unordered_map>

using namespace filament;
using namespace filament::math;
using utils::Entity;

// chess.glb uses 2-unit squares (board spans +/-8); the game uses 1-unit squares (+/-4), so every
// node is scaled 0.5 — identical to AndroidBoard3D.kt. Templates sit at the GLB origin, so a piece's
// world transform is just translate(squareCenter) * rotateY * scale(0.5).
// Keep in sync with ChessSetConventions in commonMain (single source of truth).
static constexpr float kModelScale = 0.5f;
// A board holds at most 32 pieces (promotion replaces a pawn, never adds). Instance 0 is the board;
// 1..32 are the piece-pool slots — mirrors AndroidBoard3D's createInstancedModel(MAX_PIECES + 1).
// Keep in sync with ChessSetConventions in commonMain (single source of truth).
static constexpr int kMaxPieces = 32;
static constexpr int kInstanceCount = kMaxPieces + 1;

// PieceKind ordinals (Board3DScene.kt): KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN.
// Keep in sync with ChessSetConventions in commonMain (single source of truth).
static const char* kMeshForKind[6] = { "king", "queen", "rook", "bishop", "knight", "pawn" };

namespace {

struct PieceWire {
    int kind;      // 0..5
    int color;     // 0 = white, 1 = black
    float x, y, z; // game world coords (square center, y lifts during the move-arc hop)
    float rotY;    // degrees
};

// Split on a delimiter into substrings (empty input -> empty vector).
std::vector<std::string> split(const std::string& s, char delim) {
    std::vector<std::string> out;
    if (s.empty()) return out;
    size_t i = 0, n = s.size();
    while (i <= n) {
        size_t j = s.find(delim, i);
        if (j == std::string::npos) j = n;
        out.push_back(s.substr(i, j - i));
        i = j + 1;
    }
    return out;
}

// Parse "kind,color,x,y,z,rot;kind,color,…" (Board3DScene.encode()).
std::vector<PieceWire> parseScene(const std::string& s) {
    std::vector<PieceWire> out;
    for (const std::string& rec : split(s, ';')) {
        std::vector<std::string> f = split(rec, ',');
        if (f.size() < 6) continue;
        PieceWire p{};
        p.kind  = std::atoi(f[0].c_str());
        p.color = std::atoi(f[1].c_str());
        p.x     = std::strtof(f[2].c_str(), nullptr);
        p.y     = std::strtof(f[3].c_str(), nullptr);
        p.z     = std::strtof(f[4].c_str(), nullptr);
        p.rotY  = std::strtof(f[5].c_str(), nullptr);
        out.push_back(p);
    }
    return out;
}

NSData* loadBundleResource(NSString* name, NSString* ext) {
    NSString* path = [[NSBundle mainBundle] pathForResource:name ofType:ext];
    if (!path) return nil;
    return [NSData dataWithContentsOfFile:path];
}

} // namespace

@implementation FilamentChessRenderer {
    Engine* _engine;
    SwapChain* _swapChain;
    Renderer* _renderer;
    View* _view;
    Scene* _scene;
    Camera* _camera;
    Entity _cameraEntity;
    Entity _sunlight;
    ColorGrading* _colorGrading;

    IndirectLight* _ibl;
    Skybox* _skybox;
    Texture* _iblTexture;
    Texture* _skyboxTexture;

    gltfio::AssetLoader* _assetLoader;
    gltfio::ResourceLoader* _resourceLoader;
    gltfio::MaterialProvider* _materialProvider;
    gltfio::TextureProvider* _stbProvider;
    gltfio::TextureProvider* _ktxProvider;
    utils::NameComponentManager* _names;
    gltfio::FilamentAsset* _asset;
    std::vector<gltfio::FilamentInstance*>* _instances;

    int _width;
    int _height;
    float _aspect;
    bool _ready;          // glTF resources finished loading
    bool _sceneAdded;     // asset entities added to the scene
}

- (nullable instancetype)initWithMetalLayer:(CAMetalLayer *)layer
                                  widthPx:(int)widthPx
                                 heightPx:(int)heightPx {
    self = [super init];
    if (!self) return nil;

    _width = widthPx;
    _height = heightPx;
    _aspect = heightPx > 0 ? (float)widthPx / (float)heightPx : 1.0f;
    _instances = new std::vector<gltfio::FilamentInstance*>(kInstanceCount, nullptr);

    _engine = Engine::create(Engine::Backend::METAL);
    if (!_engine) { NSLog(@"[FilamentChess] Engine::create(METAL) failed"); return nil; }

    // Bind the swap chain to the CAMetalLayer. Filament's Metal backend treats the native window
    // handle as the CAMetalLayer pointer.
    _swapChain = _engine->createSwapChain((__bridge void*)layer);
    _renderer = _engine->createRenderer();
    _scene = _engine->createScene();
    _view = _engine->createView();

    _cameraEntity = utils::EntityManager::get().create();
    _camera = _engine->createCamera(_cameraEntity);
    // Filament's default camera exposure is photographic (sunny-16, f/16 1/125 ISO100) — deliberately
    // dark for a ~30000-lux IBL, which crushed the board/pieces. Use a brighter, neutral exposure so the
    // ACES tonemapper (Filament's default, == three.js ACESFilmic) lands at the Android/web brightness.
    _camera->setExposure(16.0f, 1.0f / 125.0f, 160.0f); // TUNE: ~+0.7 stop vs default (Android-matched)

    _view->setScene(_scene);
    _view->setCamera(_camera);
    _view->setViewport({ 0, 0, (uint32_t)_width, (uint32_t)_height });
    _view->setBlendMode(View::BlendMode::TRANSLUCENT);
    _renderer->setClearOptions({.clearColor = {0.0f, 0.0f, 0.0f, 0.0f}, .clear = true});

    // Color grading: keep Filament's default ACES_LEGACY tonemapper (so the look doesn't shift) but warm
    // the white balance slightly to match the Android reference's warmer cast. temperature>0 = warmer.
    _colorGrading = ColorGrading::Builder()
        .toneMapping(ColorGrading::ToneMapping::ACES_LEGACY)
        .whiteBalance(/*temperature=*/0.10f, /*tint=*/0.0f)
        .build(*_engine);
    _view->setColorGrading(_colorGrading);

    [self setupLighting];
    if (![self loadGlb]) {
        // Asset load failed -> report failure; Kotlin falls back to 2D.
        [self shutdown];
        return nil;
    }
    return self;
}

- (void)setupLighting {
    // Image-based lighting from papermill_ibl.ktx (KTX1 with embedded spherical harmonics), plus a
    // warm directional "sun" — the same environment the Android backend bundles. Follows Filament's
    // image_based_lighting sample.
    NSData* iblData = loadBundleResource(@"papermill_ibl", @"ktx");
    if (iblData) {
        auto* bundle = new image::Ktx1Bundle((const uint8_t*)iblData.bytes, (uint32_t)iblData.length);
        float3 harmonics[9];
        bool hasSh = bundle->getSphericalHarmonics(harmonics);
        _iblTexture = ktxreader::Ktx1Reader::createTexture(
            _engine, *bundle, /*srgb=*/false,
            [](void* userdata) { delete (image::Ktx1Bundle*)userdata; }, bundle);
        IndirectLight::Builder iblBuilder;
        iblBuilder.reflections(_iblTexture);
        if (hasSh) iblBuilder.irradiance(3, harmonics);
        iblBuilder.intensity(35000.0f); // TUNE: ambient fill — lifts the dark (black) pieces
        _ibl = iblBuilder.build(*_engine);
        _scene->setIndirectLight(_ibl);
    }

    NSData* skyData = loadBundleResource(@"papermill_skybox", @"ktx");
    if (skyData) {
        auto* bundle = new image::Ktx1Bundle((const uint8_t*)skyData.bytes, (uint32_t)skyData.length);
        _skyboxTexture = ktxreader::Ktx1Reader::createTexture(
            _engine, *bundle, /*srgb=*/false,
            [](void* userdata) { delete (image::Ktx1Bundle*)userdata; }, bundle);
        _skybox = Skybox::Builder().environment(_skyboxTexture).showSun(false).build(*_engine);
        // Skybox is loaded but NOT set on the scene so the background remains transparent (matching Android SceneView)
    }

    // Warm key light, roughly matching the desktop/Android sun direction.
    _sunlight = utils::EntityManager::get().create();
    LightManager::Builder(LightManager::Type::SUN)
        .color({ 1.0f, 0.95f, 0.85f })
        .intensity(80000.0f) // TUNE
        .direction({ 0.4f, -1.0f, -0.4f })
        .castShadows(true)
        .build(*_engine, _sunlight);
    _scene->addEntity(_sunlight);
}

- (BOOL)loadGlb {
    NSData* glb = loadBundleResource(@"chess", @"glb");
    if (!glb) return NO;

    _names = new utils::NameComponentManager(utils::EntityManager::get());
    _materialProvider = gltfio::createUbershaderProvider(
        _engine, UBERARCHIVE_DEFAULT_DATA, UBERARCHIVE_DEFAULT_SIZE);
    _assetLoader = gltfio::AssetLoader::create({ _engine, _materialProvider, _names });

    // One asset, kInstanceCount instances sharing geometry but with independent transforms,
    // visibility, and material instances — mirrors Android's createInstancedModel(MAX_PIECES + 1).
    std::vector<gltfio::FilamentInstance*> instances(kInstanceCount, nullptr);
    _asset = _assetLoader->createInstancedAsset(
        (const uint8_t*)glb.bytes, (uint32_t)glb.length, instances.data(), kInstanceCount);
    if (!_asset) return NO;
    *_instances = instances;

    // Decode external/embedded resources (geometry buffers + textures). PNG via stb, KTX2 via ktx.
    gltfio::ResourceConfiguration cfg{};
    cfg.engine = _engine;
    cfg.gltfPath = "";
    cfg.normalizeSkinningWeights = true;
    _resourceLoader = new gltfio::ResourceLoader(cfg);
    _stbProvider = gltfio::createStbProvider(_engine);
    _ktxProvider = gltfio::createKtx2Provider(_engine);
    _resourceLoader->addTextureProvider("image/png", _stbProvider);
    _resourceLoader->addTextureProvider("image/jpeg", _stbProvider);
    _resourceLoader->addTextureProvider("image/ktx2", _ktxProvider);
    _resourceLoader->loadResources(_asset);

    _ready = true;
    [self configureInstanceVisibility];
    return YES;
}

// Show only the relevant geometry on each instance: instance 0 = board (tiles + frame, hide the 6
// piece templates + "Plane"); instances 1..32 each show one piece template (set later per scene).
// Hidden = removed from the scene; shown = present. Mirrors AndroidBoard3D's per-node isVisible.
- (void)configureInstanceVisibility {
    // Board instance.
    gltfio::FilamentInstance* board = (*_instances)[0];
    if (board) {
        [self forEachRenderable:board do:^(Entity e, const char* name) {
            bool isTemplate = false;
            for (int k = 0; k < 6; ++k) if (std::strcmp(name, kMeshForKind[k]) == 0) isTemplate = true;
            bool hidden = isTemplate || std::strcmp(name, "Plane") == 0;
            if (hidden) _scene->remove(e); else _scene->addEntity(e);
        }];
        [self setInstanceTransform:board pos:{0,0,0} rotYDeg:0 scale:kModelScale];
    }
    // Piece instances start hidden until setScene populates them.
    for (int i = 1; i < kInstanceCount; ++i) {
        gltfio::FilamentInstance* inst = (*_instances)[i];
        if (!inst) continue;
        [self forEachRenderable:inst do:^(Entity e, const char*) { _scene->remove(e); }];
    }
    _sceneAdded = true;
}

- (void)setSceneEncoded:(NSString *)encoded {
    if (!_ready || encoded == nil) return;
    std::vector<PieceWire> pieces = parseScene(std::string(encoded.UTF8String));

    for (int slot = 0; slot < kMaxPieces; ++slot) {
        gltfio::FilamentInstance* inst = (*_instances)[slot + 1];
        if (!inst) continue;

        if (slot >= (int)pieces.size()) {
            // No piece for this slot -> hide everything in it.
            [self forEachRenderable:inst do:^(Entity e, const char*) { _scene->remove(e); }];
            continue;
        }
        const PieceWire& p = pieces[slot];
        const char* meshName = (p.kind >= 0 && p.kind < 6) ? kMeshForKind[p.kind] : "";
        const char* materialName = (p.color == 0) ? "white" : "black";
        MaterialInstance* targetMaterial = [self materialNamed:materialName inInstance:inst];

        [self forEachRenderable:inst do:^(Entity e, const char* name) {
            bool show = (std::strcmp(name, meshName) == 0);
            if (show) {
                _scene->addEntity(e);
                if (targetMaterial) {
                    auto& rm = _engine->getRenderableManager();
                    auto ri = rm.getInstance(e);
                    size_t prims = rm.getPrimitiveCount(ri);
                    for (size_t pr = 0; pr < prims; ++pr) rm.setMaterialInstanceAt(ri, pr, targetMaterial);
                }
            } else {
                _scene->remove(e);
            }
        }];
        // y comes from the scene so the move-arc hop lifts the piece; resting pieces stay at y=0.
        [self setInstanceTransform:inst pos:{ p.x, p.y, p.z } rotYDeg:p.rotY scale:kModelScale];
    }
}

- (void)setCameraEncoded:(NSString *)encoded {
    if (encoded == nil) return;
    std::vector<std::string> f = split(std::string(encoded.UTF8String), ',');
    if (f.size() < 11) return;
    float px = std::strtof(f[0].c_str(), nullptr), py = std::strtof(f[1].c_str(), nullptr), pz = std::strtof(f[2].c_str(), nullptr);
    float tx = std::strtof(f[3].c_str(), nullptr), ty = std::strtof(f[4].c_str(), nullptr), tz = std::strtof(f[5].c_str(), nullptr);
    float ux = std::strtof(f[6].c_str(), nullptr), uy = std::strtof(f[7].c_str(), nullptr), uz = std::strtof(f[8].c_str(), nullptr);
    float fovY = std::strtof(f[9].c_str(), nullptr);
    _aspect = std::strtof(f[10].c_str(), nullptr);

    _camera->lookAt({ px, py, pz }, { tx, ty, tz }, { ux, uy, uz });

    // Portrait FOV boost: with aspect < 1 the horizontal FOV shrinks too far for the board, so widen
    // the vertical FOV to hold a fixed ~60° horizontal FOV. Identical formula to
    // CameraMath.effectiveFovYRad and AndroidBoard3D, so tap-picking stays in sync (board3d-portrait-fov-picking).
    double fov = fovY;
    if (_aspect < 1.0f) {
        double tanHalfFovX = std::tan((60.0 * M_PI / 180.0) / 2.0);
        fov = 2.0 * std::atan(tanHalfFovX / (double)_aspect) * 180.0 / M_PI;
    }
    _camera->setProjection(fov, _aspect, 0.1, 100.0, Camera::Fov::VERTICAL);
}

- (void)resizeWidth:(int)width height:(int)height {
    _width = width;
    _height = height;
    if (_view) _view->setViewport({ 0, 0, (uint32_t)width, (uint32_t)height });
}

- (void)render {
    if (!_engine || !_renderer || !_swapChain || !_view) return;
    if (_renderer->beginFrame(_swapChain)) {
        _renderer->render(_view);
        _renderer->endFrame();
    }
}

- (void)shutdown {
    if (!_engine) return;
    if (_resourceLoader) { delete _resourceLoader; _resourceLoader = nullptr; }
    if (_asset) { _assetLoader->destroyAsset(_asset); _asset = nullptr; }
    if (_stbProvider) { delete _stbProvider; _stbProvider = nullptr; }
    if (_ktxProvider) { delete _ktxProvider; _ktxProvider = nullptr; }
    if (_materialProvider) { _materialProvider->destroyMaterials(); delete _materialProvider; _materialProvider = nullptr; }
    if (_assetLoader) { gltfio::AssetLoader::destroy(&_assetLoader); _assetLoader = nullptr; }
    if (_names) { delete _names; _names = nullptr; }

    if (_colorGrading) { _engine->destroy(_colorGrading); _colorGrading = nullptr; }
    if (_ibl) { _engine->destroy(_ibl); _ibl = nullptr; }
    if (_skybox) { _engine->destroy(_skybox); _skybox = nullptr; }
    if (_iblTexture) { _engine->destroy(_iblTexture); _iblTexture = nullptr; }
    if (_skyboxTexture) { _engine->destroy(_skyboxTexture); _skyboxTexture = nullptr; }
    if (_sunlight) { _engine->destroy(_sunlight); utils::EntityManager::get().destroy(_sunlight); }

    if (_camera) { _engine->destroyCameraComponent(_cameraEntity); _camera = nullptr; }
    if (_cameraEntity) utils::EntityManager::get().destroy(_cameraEntity);
    if (_view) { _engine->destroy(_view); _view = nullptr; }
    if (_scene) { _engine->destroy(_scene); _scene = nullptr; }
    if (_renderer) { _engine->destroy(_renderer); _renderer = nullptr; }
    if (_swapChain) { _engine->destroy(_swapChain); _swapChain = nullptr; }

    Engine::destroy(&_engine);
    _engine = nullptr;
    if (_instances) { delete _instances; _instances = nullptr; }
}

// --- helpers ---

// Iterate renderable entities of an instance, resolving each glTF node name via the asset.
- (void)forEachRenderable:(gltfio::FilamentInstance*)inst do:(void(^)(Entity, const char*))block {
    auto& rm = _engine->getRenderableManager();
    const Entity* entities = inst->getEntities();
    size_t count = inst->getEntityCount();
    for (size_t i = 0; i < count; ++i) {
        Entity e = entities[i];
        if (!rm.hasComponent(e)) continue;
        const char* name = _asset->getName(e);
        block(e, name ? name : "");
    }
}

- (MaterialInstance*)materialNamed:(const char*)wanted inInstance:(gltfio::FilamentInstance*)inst {
    size_t count = inst->getMaterialInstanceCount();
    MaterialInstance* const* mis = inst->getMaterialInstances();
    for (size_t i = 0; i < count; ++i) {
        const char* n = mis[i]->getName();
        if (n && std::strcmp(n, wanted) == 0) return mis[i];
    }
    return nullptr;
}

- (void)setInstanceTransform:(gltfio::FilamentInstance*)inst
                         pos:(float3)pos
                     rotYDeg:(float)rotYDeg
                       scale:(float)scale {
    auto& tm = _engine->getTransformManager();
    Entity root = inst->getRoot();
    auto ti = tm.getInstance(root);
    if (!ti) return;
    float r = rotYDeg * (float)M_PI / 180.0f;
    mat4f t = mat4f::translation(pos)
            * mat4f::rotation(r, float3{ 0, 1, 0 })
            * mat4f::scaling(float3{ scale, scale, scale });
    tm.setTransform(ti, t);
}

@end

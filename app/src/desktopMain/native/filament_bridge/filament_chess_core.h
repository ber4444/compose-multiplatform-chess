#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace chess3d {

struct RenderResult {
    int width = 0;
    int height = 0;
    std::vector<uint8_t> rgba;
    std::string error;
};

class FilamentChessCore {
public:
    FilamentChessCore(const uint8_t* glb, int glbSize,
            const uint8_t* ibl, int iblSize,
            const uint8_t* skybox, int skyboxSize);
    ~FilamentChessCore();

    bool valid() const;
    const std::string& lastError() const;
    void resize(int width, int height);
    void setScene(const std::string& encoded);
    void setCamera(const std::string& encoded);
    RenderResult render();

private:
    struct Impl;
    Impl* impl;
};

} // namespace chess3d

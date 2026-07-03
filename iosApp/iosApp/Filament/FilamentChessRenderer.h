// Issue #54 — Metal-native Filament renderer for the iOS 3D chess board.
//
// Obj-C++ facade around Filament's C++ API. This is the ONLY header Swift sees; the .mm is the only
// translation unit that includes Filament headers, so the entire C++/static-library dependency is
// contained behind this Obj-C interface. Swift (FilamentChessView) talks to this; Kotlin never sees
// Filament at all.

#import <Foundation/Foundation.h>
#import <QuartzCore/CAMetalLayer.h>

NS_ASSUME_NONNULL_BEGIN

/// Wraps a Filament Metal engine rendering the chess board into a CAMetalLayer. All methods must be
/// called on the main thread. One frame is drawn per -render, driven by the view's CADisplayLink.
@interface FilamentChessRenderer : NSObject

/// Creates the Filament Engine (Metal backend), swap chain bound to @c layer, view/scene/camera, and
/// loads chess.glb + the papermill KTX IBL from the app bundle. Returns nil if any GPU/asset step
/// fails (the Kotlin side then falls back to 2D, same contract as the other backends).
- (nullable instancetype)initWithMetalLayer:(CAMetalLayer *)layer
                                  widthPx:(int)widthPx
                                 heightPx:(int)heightPx;

/// Board3DScene.encode(): pieces as "kind,color,x,y,z,rotYDeg" joined by ';'. Reconciled against the
/// fixed instance pool — repositions, shows/hides, and recolors pieces. No allocation per frame.
- (void)setSceneEncoded:(NSString *)encoded;

/// "px,py,pz,tx,ty,tz,ux,uy,uz,fovYDeg,aspect". The portrait FOV boost (matching
/// CameraMath.effectiveFovYRad) is applied here so picking stays in sync.
- (void)setCameraEncoded:(NSString *)encoded;

/// Resize the swap-chain drawable + viewport (physical pixels).
- (void)resizeWidth:(int)width height:(int)height;

/// Render one frame. Called from CADisplayLink; cheap no-op until the glTF asset has finished loading.
- (void)render;

/// Release all Filament + Metal resources. The renderer is unusable afterwards.
- (void)shutdown;

@end

NS_ASSUME_NONNULL_END

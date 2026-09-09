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

/// YES once chess.glb's geometry and textures are decoded and uploaded, i.e. once -render can put a
/// fully textured board on screen.
///
/// Today this is YES for the whole life of a successfully constructed renderer: -loadGlb uses
/// gltfio's *synchronous* @c ResourceLoader::loadResources, which "blocks until all textures have
/// been decoded", and -initWithMetalLayer: returns nil if it fails. It is still a real query rather
/// than an assumption because the view gates its display link on it: switching -loadGlb to
/// @c asyncBeginLoad (a tempting fix for the load-time hitch) would make it genuinely false for a
/// while, and the gate would then need this — and a per-frame @c asyncUpdateLoad — to avoid parking
/// mid-upload on an untextured board. That is exactly how the Android backend has to work, because
/// SceneView finalizes those uploads from inside its own frame loop.
@property (nonatomic, readonly, getter=isAssetReady) BOOL assetReady;

/// Render one frame. Called from the view's CADisplayLink.
///
/// Returns YES only if a frame was actually drawn: @c Renderer::beginFrame declines frames (no
/// drawable available, frame pacing), and the caller parks the display link once the board is
/// settled — so "we asked for a frame" and "the new state is on screen" must not be conflated.
- (BOOL)render;

/// Release all Filament + Metal resources. The renderer is unusable afterwards.
- (void)shutdown;

@end

NS_ASSUME_NONNULL_END

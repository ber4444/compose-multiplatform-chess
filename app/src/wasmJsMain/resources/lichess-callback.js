const url = new URL(location.href);
history.replaceState(null, "", url.pathname);
const state = url.searchParams.get("state");
if (state && /^[A-Za-z0-9_-]{16,128}$/.test(state) && "BroadcastChannel" in globalThis) {
    const channel = new BroadcastChannel("chess-lichess:" + state);
    channel.postMessage(url.href);
    channel.close();
    window.close();
}

// AardinkWebTest mounts real editors and waits for Compose to render frames. The first mount
// in a page also starts Skiko and loads the bundled font, which can outlast Mocha's default
// 2 s per-test timeout on a cold headless Chrome.
config.set({
    client: {
        mocha: {
            timeout: 15000,
        },
    },
});

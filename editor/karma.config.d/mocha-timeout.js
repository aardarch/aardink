// Mocha's default 2 s per-test timeout is too short for a test that does real work on a slow CI
// runner, and Mocha reports running out only as a bare "Error". The same limit as editor-web's.
config.set({
    client: {
        mocha: {
            timeout: 15000,
        },
    },
});

// Mocha's default 2 s per-test timeout is too short for the tests that run a whole pass over a
// large document, such as the cooperative tokenizing of every built-in language: on a slow CI
// runner they outlast it, and Mocha reports that only as a bare "Error". The same limit as
// editor-web's.
config.set({
    client: {
        mocha: {
            timeout: 15000,
        },
    },
});

// Every mounted editor takes a WebGL context that Compose Multiplatform 1.12 does not give back
// on dispose (W-1), so a suite that mounts many editors sits at headless Chrome's limit of active
// contexts, and whether a late mount still gets one depends on when the garbage collector happens
// to run. A higher limit keeps the browser tests independent of that.
config.set({
    browsers: ['ChromeHeadlessManyWebGL'],
    customLaunchers: {
        ChromeHeadlessManyWebGL: {
            base: 'ChromeHeadless',
            flags: ['--max-active-webgl-contexts=64'],
        },
    },
});

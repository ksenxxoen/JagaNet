# Web demo

The same Compose app runs in the browser (Kotlin/Wasm). The API is answered inside the page by
`composeApp/.../demo/DemoBackend.kt` (demo accounts, statistics, devices, plans, owner dashboard)
and the tunnel is simulated, so the page needs no server. Nothing is stored; reloading starts fresh.

Live demo: https://claude.ai/artifact/9L3VXx1V1sadFsLJfA9Qxa

## Build

```sh
./gradlew :composeApp:wasmJsBrowserDistribution     # bundled site in composeApp/build/dist/wasmJs/productionExecutable
```

Without the webpack step (e.g. where npm tooling can't be downloaded), the compiled ES modules can be served as they are:

```sh
./gradlew :composeApp:compileProductionExecutableKotlinWasmJsOptimize
# copy composeApp/build/compileSync/wasmJs/main/productionExecutable/optimized/jaganet.*  (mjs + wasm)
#      composeApp/build/compose/skiko-runtime-processed-wasmjs/skiko.{mjs,wasm}
#      composeApp/build/processedResources/wasmJs/main/composeResources/
#      @js-joda/core's dist/js-joda.esm.js
# and load it with an import map:  { "imports": { "@js-joda/core": "./js-joda.esm.js" } }  +  import('./jaganet.mjs')
```

Needs a current browser with WebAssembly GC (Chrome/Edge 119+, Firefox 120+, Safari 18.2+).

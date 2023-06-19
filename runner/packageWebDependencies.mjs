import * as esbuild from 'esbuild'

await esbuild.build({
    entryPoints: ['javascript/textEditor.js',
        'node_modules/bootstrap/dist/css/bootstrap.min.css',
        'node_modules/@fortawesome/fontawesome-free/css/all.min.css'],
    outdir: 'public',
    entryNames: '[name]',
    bundle: true,
    format: "esm",
    loader: {
        ".woff2": "file",
        ".ttf": "file",
    },
})

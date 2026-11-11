# Vanilla rectangle glyph investigation (2026-11-10)

Scope: official unmodified Minecraft 26.1.2 and 26.2 client jars/assets; static pixel and bytecode evidence, not live game acceptance.

## Official artifacts

- Version manifest: https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- 26.1.2 metadata: https://piston-meta.mojang.com/v1/packages/0bbfbdb8272531060afbba5c671fe4ed3dd6f72f/26.1.2.json
- 26.1.2 client jar: https://piston-data.mojang.com/v1/objects/4e618f09a0c649dde3fdf829df443ce0b8831e65/client.jar (SHA1 4e618f09a0c649dde3fdf829df443ce0b8831e65)
- 26.2 metadata: https://piston-meta.mojang.com/v1/packages/f28ad96ed5d65e2438da836144a3de15bb9cafe7/26.2.json
- 26.2 client jar: https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar (SHA1 2dc72797acbc1b63fc16a11c4ac393605f453754)
- Both jars: assets/minecraft/font/include/default.json SHA1 83145d6437f85e799ef9b8548e9eb6e5db394c1f; assets/minecraft/textures/font/ascii.png SHA1 b015d6cac492732cbbf29ad9a5e0fd463e75b84a.
- Both asset indexes share unifont JSON: https://resources.download.minecraft.net/40/402ded0eebd448033ef415e861a17513075f80e7
- Both indexes share unifont archive: https://resources.download.minecraft.net/cc/ccd5ac4767ce0a9c71d1dd62f2dc25449789b5dd

## Font selection and exact glyph

U+2588 FULL BLOCK occurs in ASCII bitmap cell column 11, row 13 (zero based). The PNG is 128x128; 16 columns/rows make an 8x8 cell at x=[88,96),y=[104,112). All 64 pixels are opaque white. Font bitmap defaults height=8, ascent=7. BitmapProvider scales by height/cellHeight=1; advance=round(actualRightmostInkWidth*scale)+1=9. Bearing left=0; GlyphBitmap top=7-ascent=0, bottom=8. Ink is [0,8]x[0,8] in font coordinates, NOT a 9x9 filled tile.

Use explicit `minecraft:include/default` for this measured bitmap glyph. Its provider entries have no Unicode-option filter. FontManager loads all font/**/*.json definitions as addressable font IDs. The parent `minecraft:default` references this include with `uniform:false`; therefore using `minecraft:default` is NOT stable under Force Unicode Font.

Under `minecraft:uniform` (or default + Force Unicode), U+2588 is unifont hex `2588:FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF`: 8 samples x16 samples, with UnihexProvider oversample=2. Logical ink is 4x8, advance=8/2+1=5, bearing left0, top0. Thus blindly assuming default U+2588 is always8x8 introduces a factor-of-two width error for Unicode mode.

All metrics assume no bold, italic, shadow, underline, strikethrough or obfuscation; a resource pack overriding builtin fonts/textures can change these facts. Our own display_geometry font is not involved.

## TextDisplay origin and independent scale

DisplayRenderer.TextDisplayRenderer.submitInner does:

1. matrix.rotate(pi,0,1,0)
2. matrix.scale(-0.025,-0.025,-0.025)
3. text width = maximum line advance, line total height = lineCount*10-1
4. matrix.translate(1-width/2,-height,0)
5. renders a single glyph at line x=0,y=0.

For one ASCII U+2588, width=9,height=9. The composed conversion before entity transform is x=0.025*(fontX-3.5), y=-0.025*(fontY-9). Therefore actual ink bounds are entity X[-0.0875,+0.1125], Y[+0.025,+0.225], width/height0.2 blocks, center(+0.0125,+0.125). The TextDisplay entity origin is not the ink center or top edge.

For target width W, height H in blocks, choose sx=W/0.2, sy=H/0.2. For target center(Cx,Cy) in entity coordinates (Y up), use translation(Cx-0.0125*sx,Cy-0.125*sy). Equivalent target top-left(L,T): translation(L+0.0875*sx,T-0.225*sy). Rotate these translation offsets along with the mounted plane; do not apply as world-Y offsets after plane rotation. In a layout with Y down, convert target layout anchors to this entity-up basis once.

Vanilla TextDisplay background must be fully transparent; otherwise its independently padded background quad is not this glyph. Set component flags/styles explicitly neutral to prevent inherited bold/italic etc.

26.2 submitInner bytecode has identical origin/scale constants. Atlas UV inset0.01 samples only changes UVs, not glyph geometry: GlyphBitmap left/right/top/bottom pass unchanged into BakedSheetGlyph.

## Local reproduction evidence

- /tmp/mc-26.1.2-client.jar, /tmp/mc-26.2-client.jar
- /tmp/mc-26.1.2.json, /tmp/mc-26.2.json, /tmp/mc-26.1.2-assets.json, /tmp/mc-26.2-assets.json
- /tmp/mc-26.1.2-ascii.png, /tmp/mc-26.2-ascii.png
- /tmp/mc-unifont.json, /tmp/mc-unifont.zip
- /tmp/mc-glyph-bytecode.txt (BitmapProvider.Glyph, BitmapProvider.Glyph$1, TextDisplayRenderer)
- /tmp/mc-provider-bytecode.txt (BitmapProvider.Definition, UnihexProvider.Glyph and Glyph$1)
- /tmp/mc-unihex-glyph2.txt (oversampling2, 16 sample-height)
- /tmp/mc-fonttexture-bytecode.txt, /tmp/mc-bakedglyph.txt, /tmp/mc-fontmanager.txt, /tmp/mc-fontset.txt, /tmp/mc262-textdisplay.txt

No repository or server mutation was performed in this investigation.

## Existing custom circle calibration

Read-only inspection of current `scripts/display-assets/generate.py` and actual PNG alpha bounds confirms ring.png/dot.png are20x18 samples, ink bounds(2,0,20,18). Bitmap height9 gives oversample2: quad10x9, inkX[1,10],inkY[0,9]. Actual rightmost ink width20samples => advance round(20*.5)+1=11. TextDisplay translateX1-11/2=-4.5 makes inkX[-3.5,+5.5], center+1 logical pixel. Therefore existing spans scaled size/9 need x-size/9 to center ink at requestedx. Their top is9 logicalpixels above entityorigin; bottom isexactlyorigin; no vertical center correction isneeded if existing size9 topanchor convention isalreadycorrect. The generator comment claiming the one logicalpixel padding balances centered text is wrong: vanilla extra+1 TextDisplaytranslation was omitted from that reasoning.

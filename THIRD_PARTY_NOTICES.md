# Third-Party Notices

COP distributes modified third-party source components and bundles several
libraries in its production JAR. These notices apply only to the named
third-party components; COP itself is licensed under GPL-3.0-only as stated in
`LICENSE`. Additional verbatim license texts are stored under `LICENSES/` and
are included in production JARs together with this file.

## Quoi code family — GPL-3.0-only

Current inspected source: https://github.com/jcnlk/quoi

Authors named by snapshot `1.1.1+26.1`: frogs and jcnlk

Historical COP source reference: https://github.com/pigeonlover1998/quoi

COP retains older Quoi-derived UI, module, event, HUD and settings code and is
distributed under the same GPL-3.0-only license. The current snapshot was also
used as a behavioural reference for several independently rewritten local
features, including class-ability, scanned-door, Dungeon-Potion,
invincibility, Wither-Cloak and Barrier-Boom automation, inventory presets,
economy/activity helpers and the local player-packet safeguards. Those new
features were written for COP; no Quoi archive, endpoint, downloader or asset
is bundled. The complete GPL-3.0 license text is present in COP's `LICENSE`
file.

## Material Color Utilities HCT math — Apache-2.0

Source: https://github.com/material-foundation/material-color-utilities

Copyright 2025 Google LLC.

COP includes an adapted Kotlin HCT/color-math implementation in
`cop/utils/HctMath.kt`. The source header is retained. The complete Apache
License 2.0 is provided in `LICENSES/Apache-2.0.txt`.

## Odin, Athen, Nebulune and rsm source components — BSD 3-Clause

Sources:

- https://github.com/odtheking/Odin — Copyright (c) 2025, odtheking
- https://github.com/skies-starred/Athen — Copyright (c) 2025-2026, Starred
- https://github.com/skies-starred/Nebulune — Copyright (c) 2025, Starred
- https://github.com/rs-mod/rsm — Copyright (c) 2026, rice.who

COP contains modified or ported components identified in their source headers
and in `CREDITS.md`, including dungeon state/scanning and solvers, rendering/UI
utilities, selected Athen/Nebulune modules and `MutableInput`. The following
BSD 3-Clause terms apply to those components and their respective copyright
notices above:

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Stella and Skyblocker source components — LGPL-3.0-only

Sources:

- https://github.com/Eclipse-5214/stella — Copyright Eclipse-5214 and contributors
- https://github.com/SkyblockerMod/Skyblocker — Copyright Skyblocker contributors

COP contains modified Stella world/legacy-ID helpers and modified Skyblocker
Ice Path/Tic-Tac-Toe solver components. Their source headers and upstream links
are retained. COP publishes the complete corresponding source under
GPL-3.0-only, as permitted by LGPLv3 section 2(b). The complete LGPLv3 text is
provided in `LICENSES/LGPL-3.0-only.txt`; the incorporated GPLv3 text is COP's
root `LICENSE`.

## NoobRoutes SecretAura — Unlicense

Source: https://github.com/Hypericat/NoobRoutes

The `SecretAura` implementation retains its upstream source link. NoobRoutes
is released under the Unlicense:

This is free and unencumbered software released into the public domain.

Anyone is free to copy, modify, publish, use, compile, sell, or distribute this
software, either in source code form or as a compiled binary, for any purpose,
commercial or non-commercial, and by any means.

In jurisdictions that recognize copyright laws, the author or authors of this
software dedicate any and all copyright interest in the software to the public
domain. We make this dedication for the benefit of the public at large and to
the detriment of our heirs and successors. We intend this dedication to be an
overt act of relinquishment in perpetuity of all present and future rights to
this software under copyright law.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

For more information, please refer to https://unlicense.org/.

## GPL-3.0-compatible source components and datasets

COP retains source-level attribution for adapted components or data from the
following GPL-3.0 projects. COP's complete corresponding source and the full
GPL-3.0 text are provided by this repository and its root `LICENSE`:

- devonian: https://github.com/Synnerz/devonian
- Meteor Client: https://github.com/MeteorDevelopment/meteor-client
- GumTuneClient: https://github.com/RoseGoldIsntGay/GumTuneClient
- Client-Custom-Name: https://github.com/Ownwn/Client-Custom-Name
- Secret Routes Mod: https://github.com/yourboykyle/SecretRoutes
- historical NoammAddons/CatgirlAddons references listed in `CREDITS.md`

## libautoupdate 1.3.1 — BSD 2-Clause

Source: https://git.nea.moe/nea/libautoupdate/

Copyright (c) 2022 Linnea Gräf.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## ClassGraph 4.8.184 — MIT

Source: https://github.com/classgraph/classgraph

Copyright (c) 2019 Luke Hutchison

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## LWJGL NanoVG 3.4.1 and native libraries — BSD 3-Clause

Source: https://github.com/LWJGL/lwjgl3

Copyright (c) 2012-present Lightweight Java Game Library
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice,
  this list of conditions and the following disclaimer.
- Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.
- Neither the name Lightweight Java Game Library nor the names of its
  contributors may be used to endorse or promote products derived from this
  software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

# Development references not bundled

The following projects or snapshots were inspected as behavioural, data or
architecture references while improving COP. Their archives and foreign
runtime or networking code are not bundled in COP's production JAR.

## CritsAddons and RandomStuff/AutoCroesus — behavioural references only

Sources:

- https://github.com/FateShop/CritsAddons
- https://github.com/UnclaimedBloom6/RandomStuff/tree/main/AutoCroesus

The inspected CritsAddons repository is marked `All rights reserved`, while the
RandomStuff repository does not publish a repository-level software license.
Older COP revisions described several CritsAddons features as ports and the
Croesus parser as an adaptation. For `1.8.0-beta.1`, all six affected Dungeon
modules and the parser in the current source tree were replaced by clean-room
implementations written from functional specifications and COP's public APIs,
without consulting those reference implementations. Consequently, the current
tree and generated artifacts contain no implementation code from either
project. Literal server/menu strings are facts of the Hypixel protocol and UI.

## dtMap — BSD 3-Clause

Source: https://github.com/ricedotwho/dtMap

Copyright (c) 2026, rice.who

dtMap was used as a conceptual reference for deriving the Dungeon Map topology
from local map state.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## NoammAddons 26.1.2 snapshot — CC0 1.0 Universal

Source: https://github.com/Noamm9/NoammAddons

The root `LICENSE` file present in the inspected 26.1.2 snapshot contains the
Creative Commons **CC0 1.0 Universal** legal code. The snapshot was used only
as a conceptual comparison for the Dungeon Map, Item Protection, local dungeon
HUDs/alerts, active-terminal display, Auto I4, Last-Breath debuffs, Dungeon
requeue, Architect's Draft and M7 Twilight refills. It also contains embedded components
and assets with separate or unclear provenance, so COP does not bundle the
snapshot archive, its assets, foreign runtime/networking code, WebSocket logic,
downloaders or Noamm service endpoints.

CC0 1.0 Universal legal code:
https://creativecommons.org/publicdomain/zero/1.0/legalcode

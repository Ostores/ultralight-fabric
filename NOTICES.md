# Third-party notices

This project's own source code is licensed under the MIT License (see `LICENSE`).
Release builds (the distributed JAR) additionally bundle the third-party components
below, each under its own license.

## Ultralight (SDK + binaries)

© Ultralight, Inc. Proprietary. Used under the **Ultralight Free License**
(<https://ultralig.ht/pricing> — free for individuals/companies under US$100k
revenue/funding, PC platforms, application use). **Attribution is required.**

> This application uses the Ultralight rendering engine.
> Ultralight is © Ultralight, Inc. All rights reserved.

When distributing a build that bundles the Ultralight binaries, include Ultralight's
own `NOTICES.txt` (shipped inside the SDK archive) as required by its license.
The Ultralight binaries are **not** redistributed in this source repository.

## Luminescence

Java/JNI binding to Ultralight — <https://github.com/Solomon-Team/Luminescence>
© Ayydxn / Solomon-Team. Licensed under **LGPL-3.0**. The Luminescence library is
used as a separate, replaceable component (its jars are not modified). Its source is
available at the URL above.

## Noto Fonts (bundled)

The release JAR bundles the following Google **Noto** fonts as embedded fallbacks
(emoji, non-emoji symbols, and a guaranteed-loadable text last-resort — the latter
notably works around a null-font crash in the Ultralight macOS font path):

- **Noto Emoji** (`NotoEmoji-Regular.ttf`)
- **Noto Sans Symbols 2** (`NotoSansSymbols2-Regular.ttf`)
- **Noto Sans** (`NotoSans-Regular.ttf`)

© The Noto Project Authors. Licensed under the **SIL Open Font License 1.1**
(<https://openfontlicense.org>). Source: <https://github.com/notofonts/notofonts.github.io>.

## ICU4J

© Unicode, Inc. Licensed under the **Unicode License** (permissive).
<https://github.com/unicode-org/icu>

## Minecraft / Fabric

This is a mod for Minecraft using the Fabric toolchain; it does not redistribute
Minecraft or Fabric binaries.

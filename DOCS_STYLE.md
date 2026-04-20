# oqx Documentation Style

## Core product wording

- Lead with: `oqx` is a **CLI-first, file-backed SOAP workbench**.
- Use **file-backed** for the overall workflow and product shape.
- Use **file-first** only when discussing on-disk layout or inspectability.

## Product vs implementation

- Keep most docs product-first.
- Put Java/runtime/library choices inside **Current implementation direction** sections or other explicitly implementation-focused sections.
- Prefer: **The first implementation is Java-first.**

## Preferred terms

- **Java version:** "latest Java, currently Temurin 26"
- **CXF's role:** "Apache CXF at the integration edge" or "Apache CXF as an integration layer at the edge"
- **Contract model:** "custom, normalized, product-owned contract model"
- **Model vs index:** use **normalized contract model** for the internal representation; use **queryable index** or **normalized index** only for persisted/query-oriented artifacts derived from it
- **Storage:** "TOML for user-edited project/env/auth/request-sidecar config, JSON for internal cached/indexed state under `.oqx/`, XML for user-owned request files"
- **Packaging:** "runnable JAR first; `jpackage` later if distribution needs it"
- **TOML library:** when implementation details matter, say **NightConfig (TOML module)**

## Negative language

Prefer explicit wording over shorthand:

- **do not use YAML**
- **do not target Graal native image**
- **avoid JPMS/jlink-first design pressure early on**

In tight bullet lists, `no YAML` is acceptable, but the longer forms are preferred in narrative text.

## Tone

- Prefer direct, plain wording.
- Use **is** for decisions already made.
- Use **should** for architecture guidance and intended behavior.
- Avoid turning product docs into dependency catalogues.
- When naming libraries, say what role they play rather than listing them without context.

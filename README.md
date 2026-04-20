# oqx

`oqx` is a **CLI-first, file-backed SOAP workbench**.

The product remains scoped around a text-first, file-backed workflow built on:

- pinned WSDL imports
- normalized contract indexing
- persistent request files
- `$EDITOR`-based XML editing
- render/send flows with environment and auth injection
- redacted history on disk

## Current implementation direction

The first implementation is **Java-first**.

- **Runtime target:** latest Java, currently **Temurin 26**
- **CLI:** **picocli** for subcommands, help, completions, and man-page-friendly UX
- **SOAP/WSDL integration:** **Apache CXF**, kept at the integration edge for dynamic client / `Dispatch` / interceptors / WS-Security integration
- **Contract model:** custom, normalized, and product-owned rather than a direct CXF or JAXB runtime model
- **XML parsing/streaming:** **Woodstox**
- **XML binding:** **JAXB RI**, used selectively where binding helps, not as the default representation
- **Config/storage:** **TOML** for user-edited project/env/auth/request-sidecar config, **JSON** for internal cached/indexed state under `.oqx/`, and **XML** for user-owned request files
- **TOML library:** **NightConfig** (TOML module), chosen for comment-aware read/write of user-edited config
- **Packaging:** runnable **JAR first**; **`jpackage` later** if distribution needs it
- **Explicit non-goals:** do not use YAML, do not target Graal native image, and avoid JPMS/jlink-first design pressure early on

## Docs

- [`ARCHITECTURE.md`](./ARCHITECTURE.md) — architecture, storage model, contracts, environments, auth, history, guardrails, and Java-first implementation boundaries
- [`CLI_FLOW.md`](./CLI_FLOW.md) — command flow, end-to-end usage, WSDL update behavior, and v1 scope
- [`DOCS_STYLE.md`](./DOCS_STYLE.md) — preferred wording, implementation terminology, and documentation conventions for future repo docs

## Project status
This repository now includes a minimal Java/Maven scaffold plus dependency-free local install and uninstall scripts:
- `src/main/java/oqx/Main.java` — hello-world entrypoint
- `scripts/InstallLocal.java` — dependency-free local installer runnable as `java scripts/InstallLocal.java`
- `scripts/UninstallLocal.java` — dependency-free local uninstaller runnable as `java scripts/UninstallLocal.java`

### Local install / uninstall

- Install via Maven: `mvn exec:exec@install-local`
- Uninstall via Maven: `mvn exec:exec@uninstall-local`
- Install via Java: `java scripts/InstallLocal.java`
- Uninstall via Java: `java scripts/UninstallLocal.java`

Both scripts also support `--install-dir <path>`.

Examples:

- `mvn exec:exec@install-local -Doqx.installLocal.args="--install-dir <path>"`
- `mvn exec:exec@uninstall-local -Doqx.uninstallLocal.args="--install-dir <path>"`
The broader product is still in the architecture-first stage; the CLI implementation itself is only a small bootstrap prototype so far.
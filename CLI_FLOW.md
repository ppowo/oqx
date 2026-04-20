# oqx CLI Flow

## Flow at a glance

`oqx` is built around a text-first loop:

```txt
import contract -> inspect operations -> generate request -> edit in $EDITOR -> render -> send -> inspect history
```

By default, envelopes are **auto-generated from the normalized contract model** and then turned into **persistent request files** that the user owns.

---

## Core commands

| Command | Purpose | Typical result |
| --- | --- | --- |
| `oqx init` | Create a new file-backed project | `oqx.yaml`, `requests/`, `env/`, `auth/`, `.oqx/` |
| `oqx import` | Pin a WSDL, resolve imports, normalize it, compute fingerprints | new contract revision in `.oqx/contracts/...` |
| `oqx ops` | List callable operations from the normalized index | operation names, bindings, ports, SOAP version, warnings |
| `oqx example` | Print a starter envelope/template for an operation | XML to stdout |
| `oqx new` | Create a persistent request file for an operation | `requests/<name>.xml` + sidecar metadata |
| `oqx edit` | Open a request file in `$EDITOR` | user edits durable XML |
| `oqx render` | Show the final message after env/auth/override injection without sending | rendered XML preview |
| `oqx send` | Render, transmit, and snapshot the run | response + redacted history entry |
| `oqx history` | Inspect prior sends | run list, metadata, response snapshots |

### Optional but useful commands

| Command | Purpose |
| --- | --- |
| `oqx replay` | resend a previous history entry, optionally into another environment |
| `oqx diff` | compare contract revisions or compare a request against regenerated output |
| `oqx regen` | generate a new candidate request from the current contract revision |
| `oqx status` | show active contract, request staleness, environment selection, and warnings |

---

## What each command should do

### `oqx init`
Create the local project skeleton.

Typical behavior:

- write `oqx.yaml`
- create `requests/`, `env/`, `auth/`, `.oqx/`
- optionally create starter `env/test.yaml` and `env/prod.yaml`

Example:

```bash
oqx init porto-demo
cd porto-demo
```

### `oqx import`
Import a WSDL into the project.

Typical behavior:

- copy/pin the source WSDL
- resolve imports/includes
- normalize into an internal contract index
- compute full and semantic fingerprints
- record import warnings

Example:

```bash
oqx import /Users/pun/Downloads/GPFWebService.xml --contract gpfwebservice
```

### `oqx ops`
List available operations from the normalized contract model.

Typical behavior:

- show operations by binding/port
- show SOAP version and style/use when helpful
- surface warnings such as empty `soapAction`

Example:

```bash
oqx ops
```

### `oqx example`
Print a generated example envelope to stdout.

Use this when you want a quick preview without creating files.

```bash
oqx example getInfoMerce
```

### `oqx new`
Create a real request file for an operation.

Typical behavior:

- auto-generate a starter envelope/body
- write `requests/<name>.xml`
- write sidecar metadata with operation + contract fingerprint

```bash
oqx new getInfoMerce --name get-info-merce
```

### `oqx edit`
Open the persistent request file in `$EDITOR`.

```bash
oqx edit requests/get-info-merce.xml
```

This is a convenience command; the user can also edit the file directly with any editor.

### `oqx render`
Render the final outbound XML without sending it.

Typical behavior:

- apply selected environment
- inject transport/message auth as needed
- apply endpoint/header/`SOAPAction` overrides
- print or save the final envelope for inspection

```bash
oqx render requests/get-info-merce.xml --env test
```

### `oqx send`
Render and transmit the request, then save an immutable history snapshot.

Typical behavior:

- load request + sidecar metadata
- warn if the contract fingerprint is stale
- apply env/auth/override data
- send the request
- separate SOAP faults from transport errors
- store request/response snapshots in `.oqx/history/`

```bash
oqx send requests/get-info-merce.xml --env test
```

### `oqx history`
Show prior runs and allow inspection/replay.

```bash
oqx history
```

---

## End-to-end example with the real GPF WSDL

A real WSDL was found at:

```txt
/Users/pun/Downloads/GPFWebService.xml
```

It appears to describe:

- service: `GPFWebService`
- binding: `GPFWebServiceSoapBinding`
- port: `GPFWebServicePort`
- endpoint: `https://sinfomar.porto.trieste.it/GPFWebService/GPFWebService`
- SOAP 1.1 document/literal behavior
- many operations with empty `soapAction=""`

A realistic workflow should look like this:

```bash
oqx init porto-demo
cd porto-demo

oqx import /Users/pun/Downloads/GPFWebService.xml --contract gpfwebservice
oqx ops
oqx example getInfoMerce
oqx new getInfoMerce --name get-info-merce
oqx edit requests/get-info-merce.xml
oqx render requests/get-info-merce.xml --env test
oqx send requests/get-info-merce.xml --env test
oqx history
```

### What happens in that flow

1. `oqx import` pins the WSDL and builds a normalized index.
2. `oqx ops` lists the operations available from that index.
3. `oqx example` shows the generated starter envelope.
4. `oqx new` creates a persistent request file on disk.
5. `oqx edit` opens the file so the user can fill in business payload values.
6. `oqx render` shows the final outbound XML after environment and auth injection.
7. `oqx send` transmits it and stores history snapshots.
8. `oqx history` lets the user inspect what happened.

---

## Are envelopes auto-generated?

Yes, by default.

- `oqx example <operation>` prints a generated starter envelope
- `oqx new <operation>` writes a generated envelope into a request file

The generated output is only a starting point.

After generation, the XML belongs to the user. `oqx` should not keep trying to reformat or rewrite it silently.

### Important exception: raw/manual mode

Because WSDLs often drift or lie, `oqx` must also support fully manual sends.

Example:

```bash
oqx send --file requests/raw.xml \
  --endpoint https://sinfomar.porto.trieste.it/GPFWebService/GPFWebService \
  --soap-action '' \
  --header 'X-Debug: 1'
```

That path must work even if no valid WSDL is currently imported.

---

## Why requests are persistent files instead of temp files

Persistent request files are a core design choice.

They allow users to:

- keep working examples under version control
- diff changes over time
- review exactly what was edited
- preserve namespace/order-sensitive XML
- reuse requests across sessions
- keep using an older request even after a WSDL revision changes

Temp files are still fine for scratch workflows, but they should not be the default model.

---

## What happens when a WSDL changes

WSDL updates should be explicit, not silent.

Example:

```bash
oqx import /Users/pun/Downloads/GPFWebService.xml --update
```

Expected behavior:

1. import the new revision
2. compute a new fingerprint
3. diff the old normalized contract against the new one
4. classify the change
5. mark affected requests as stale
6. keep existing request files untouched
7. suggest `oqx diff`, `oqx status`, or `oqx regen`

`oqx regen` should create a new candidate request file. It should never overwrite the existing request automatically.

---

## How auth is applied during render/send

Auth should be attached as late as possible.

### During `render`

- select environment
- load default or explicit auth profile
- resolve secret references from env/file/keychain/prompt
- inject transport headers and/or WS-Security message headers
- show the final envelope without mutating the saved working request file

### During `send`

- do the same render-time auth work
- apply transport-level behavior such as basic auth, bearer tokens, mTLS, proxy auth
- transmit the final message
- store redacted snapshots in history

Requests should not contain secrets by default.

---

## Must-have v1 behavior

These are the minimum behaviors that make the CLI useful.

| v1 must-have | Why it matters |
| --- | --- |
| import a local WSDL and normalize it once | avoids reparsing and gives a stable contract model |
| list operations | users need a discovery step |
| generate starter envelopes | saves manual SOAP boilerplate work |
| create persistent request files | supports real iterative workflows |
| edit via `$EDITOR` | text-first control is the core UX |
| render without sending | lets users inspect injected auth/headers safely |
| send requests and capture history | makes the tool an actual workbench |
| separate contract/env/auth | prevents configuration sprawl and hidden coupling |
| warn on stale requests after contract changes | keeps users safe without destroying work |
| support raw/manual send mode | necessary because real SOAP/WSDL setups are often broken |
| support endpoint/header/`SOAPAction` overrides | required for interoperability in the real world |
| keep history redacted | avoids leaking secrets into local state |

---

## Nice-to-have later

Good follow-up features, but not required for the first useful version:

- WS-Security UsernameToken helpers
- WS-Security signatures and timestamps
- richer schema-aware request generation
- better contract diff presentation
- replay history into another environment
- inline validation against imported schema
- shell completion and richer TUI output
- import from URL with caching policies
- smarter fault summarization and troubleshooting hints

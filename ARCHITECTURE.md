# oqx Architecture

## Mental model

`oqx` is a **CLI-first SOAP workbench**.

It should feel closer to `git`, `kubectl`, or `terraform` than to a desktop GUI:

- commands are explicit
- request artifacts live on disk
- users edit XML in `$EDITOR`
- imports are pinned and reproducible
- sends create history snapshots
- the tool stays useful even when the WSDL is incomplete, stale, or wrong

The default workflow is:

1. import a WSDL once
2. normalize and index it into an internal contract model
3. generate a starter request/envelope
4. edit the request file manually
5. render the final message with environment/auth/override data
6. send it and store redacted history on disk

The CLI is the product. Any future UI should be a thin layer over the same file-backed model.

---

## Separation of concerns

`oqx` should keep five concerns separate.

| Concern | Owns | Must not own |
| --- | --- | --- |
| **Contract** | WSDL/XSD imports, normalized operations, bindings, message shapes, contract fingerprints | runtime secrets, environment-specific endpoint choices, mutable user request bodies |
| **Environment** | endpoint URL, proxy, TLS policy, timeout, default headers, default auth profile selection | WSDL structure, request payload templates |
| **Auth profile** | auth mechanism and secret references for transport or message auth | business payload data, contract structure |
| **Request files** | user-authored XML payload/envelope plus lightweight request metadata | secret storage, mutable contract definitions |
| **History / snapshots** | immutable records of sends, redacted request/response copies, status, timing, fault details | editable working requests, canonical contract source |

### Contract
A contract is the imported SOAP description:

- WSDL source
- resolved imports/includes
- services, ports, bindings, operations
- schema elements/types needed for example generation and validation
- fingerprints used for staleness detection and diffing

### Environment
An environment describes **where** and **how** to send:

- endpoint URL
- proxy settings
- TLS and client cert references
- timeout/retry knobs
- default headers
- default auth profile

Examples: `test`, `uat`, `prod`.

### Auth profile
An auth profile describes **how credentials are applied**.

Examples:

- none
- basic auth
- bearer token
- API key header
- mTLS
- later: WS-Security UsernameToken / signature profiles

Requests should point at profiles; they should not embed secrets by default.

### Request files
Requests are **real files on disk**, not temporary buffers by default.

They exist so users can:

- diff them
- commit them
- copy them
- hand-edit namespace-sensitive XML
- keep durable working examples even when the WSDL later changes

Request metadata should live outside the XML when possible, for example in a sidecar file.

### History / snapshots
History is append-only and immutable.

It should capture:

- rendered request snapshot
- response snapshot
- redacted request/response variants
- HTTP status
- SOAP fault details
- selected environment/auth profile
- timestamps and duration
- contract fingerprint used at send time

---

## Recommended on-disk project layout

A project should be file-first and easy to inspect without the CLI.

```txt
.
├── oqx.yaml
├── requests/
│   ├── getInfoMerce.xml
│   └── getInfoMerce.oqx.yaml
├── env/
│   ├── test.yaml
│   └── prod.yaml
├── auth/
│   ├── basic-test.yaml
│   └── mtls-prod.yaml
└── .oqx/
    ├── contracts/
    │   └── gpfwebservice/
    │       └── revisions/
    │           ├── 2026-04-20T12-00-00Z/
    │           │   ├── source.wsdl
    │           │   ├── resolved-index.json
    │           │   ├── normalized-contract.json
    │           │   ├── full-fingerprint.txt
    │           │   ├── semantic-fingerprint.txt
    │           │   └── import-report.md
    │           └── current.txt
    ├── cache/
    │   └── imports/
    └── history/
    │       └── 2026-04-20/
    │           └── 2026-04-20T12-31-22Z_getInfoMerce/
    │               ├── meta.yaml
    │               ├── request.rendered.xml
    │               ├── request.redacted.xml
    │               ├── response.raw.xml
    │               └── response.redacted.xml
```

### Notes

- `oqx.yaml` stores project-level defaults and the active contract.
- `requests/*.xml` are user-owned working files.
- `requests/*.oqx.yaml` can store metadata like operation, contract name, contract fingerprint, defaults, and last-known status.
- `env/*.yaml` contains deploy-target concerns.
- `auth/*.yaml` contains auth mechanism definitions and secret references.
- `.oqx/` is internal tool state: imported contract revisions, caches, and history.

---

## Normalized WSDL import and indexing model

`oqx import` should treat the WSDL as input to a one-time normalization pipeline.

### Import pipeline

1. Read the provided WSDL, even if the file extension is just `.xml`.
2. Resolve WSDL/XSD imports and includes.
3. Cache imported artifacts locally for repeatable use.
4. Build a canonical internal model.
5. Persist the normalized model and fingerprints.
6. Emit import warnings for ambiguous or suspicious WSDL constructs.

### Normalized contract model

The normalized model should answer CLI questions without reparsing the raw WSDL every time.

It should contain at least:

- contract name
- detected SOAP version
- services, ports, and bindings
- operation list
- input/output/fault message roots
- style/use information
- `soapAction` values, including empty-string cases
- relevant namespaces
- resolved schema references needed for example generation
- warnings about unsupported or lossy cases

The CLI should operate from this normalized model for:

- `oqx ops`
- `oqx example`
- `oqx new`
- staleness checks
- contract diffs

The raw WSDL remains pinned for traceability, but the internal index is the runtime source of truth.

---

## Fingerprinting and WSDL update handling

WSDL changes should be treated as **schema migrations**, not as silent updates.

### Fingerprints

Store two fingerprints:

1. **Full fingerprint**
   - sensitive to the pinned source and resolved imports
   - useful for exact reproducibility

2. **Semantic contract fingerprint**
   - derived from the normalized contract model
   - ignores transport-only values such as `soap:address`
   - useful for deciding whether test/prod WSDLs describe the same callable contract

### Update flow

A re-import should be explicit, for example:

```bash
oqx import ./new.wsdl --update
```

That update flow should:

1. parse and normalize the new WSDL revision
2. compare old vs new normalized contract
3. classify the change
4. save a new pinned revision
5. mark affected requests as stale
6. never overwrite user XML automatically

### Useful change classes

- endpoint-only change
- additive operation/type change
- binding/soapAction change
- request/response shape change
- removed or renamed operation

### Request staleness

A request sidecar should record:

- logical contract name
- contract fingerprint
- operation name
- optionally generated element/type roots

If the active contract revision changes and the request no longer matches, `oqx` should warn on `render`/`send` and suggest:

- `oqx diff`
- `oqx status`
- `oqx regen`

`oqx regen` should create a **new candidate request**, not overwrite the existing file.

---

## Handling prod/test WSDLs

Different environments should not automatically imply different contracts.

### Same contract, different environments
Use **one contract + multiple env files** when the semantic contract is the same and only deployment details differ, such as:

- endpoint URL
- proxy
- certificates
- default auth
- transport headers

If only `soap:address` changes, this is almost certainly the same contract.

### Different contract revisions or variants
Use **separate contract revisions/variants** when the semantic contract differs, such as:

- operation list changes
- message parts differ
- element/type structure changes
- binding semantics differ in a meaningful way

Requests should bind to:

- logical contract name
- contract fingerprint
- operation name

That keeps a request portable across `test` and `prod` only when the underlying contract is actually compatible.

---

## Auth model

Auth must be split between **transport auth** and **message auth**.

### Transport auth
Applied at the HTTP/TLS layer:

- basic auth
- bearer token
- API key headers
- mTLS
- proxy auth

### Message auth
Applied inside the SOAP message during `render`/`send`:

- WS-Security UsernameToken
- later: signatures, timestamps, and other WS-Security policies

### Secret sources
Secrets should be referenced, not hardcoded.

Supported reference styles should include:

- `env:NAME`
- `file:/path/to/secret`
- `keychain:service/account`
- `prompt`

Example:

```yaml
kind: basic
username: env:OQX_USER
password: keychain:porto/gpf/password
```

### Application timing

Auth should be applied at **render/send time**, not baked permanently into request files.

That means:

- transport headers are assembled from env + auth profile
- WS-Security headers are injected into the final rendered envelope
- secrets stay out of committed working XML unless the user intentionally writes them there

---

## Guardrails

The CLI should preserve manual control and avoid destructive magic.

### Never silently rewrite user requests

- importing a new WSDL must not mutate existing XML files
- `regen` writes a new candidate file
- the user decides whether to merge changes

### Always support raw/manual mode

The tool must still work when the WSDL is missing, stale, or wrong.

Examples:

```bash
oqx send --file requests/raw.xml --endpoint https://example.test/soap
oqx send --file requests/raw.xml --endpoint https://example.test/soap --soap-action urn:manual
oqx send --file requests/raw.xml --header 'X-Debug: 1'
```

### Keep history redacted

History should default to redacted snapshots so tokens, passwords, and sensitive headers are not casually persisted.

### Support overrides

Users must be able to override at render/send time:

- endpoint
- headers
- `SOAPAction`
- possibly timeout/proxy/TLS knobs

This matters because real SOAP systems often drift away from the published WSDL.

### Preserve XML control

Do not over-normalize user-authored request XML.

The CLI should respect that some integrations depend on exact namespace choices, ordering, whitespace, or manually crafted headers.

---

## Practical example: GPFWebService

A real example WSDL was identified at:

```txt
/Users/pun/Downloads/GPFWebService.xml
```

Important observations:

- it is a WSDL despite the `.xml` suffix
- service: `GPFWebService`
- binding: `GPFWebServiceSoapBinding`
- port: `GPFWebServicePort`
- endpoint: `https://sinfomar.porto.trieste.it/GPFWebService/GPFWebService`
- it appears to be SOAP 1.1 document/literal
- many operations expose `soapAction=""`

`oqx` should import this successfully, preserve the empty-action behavior by default, and still allow manual action overrides when real-world server behavior requires it.

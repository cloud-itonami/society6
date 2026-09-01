# society6

`cloud-itonami/society6` は Society6 actor の **cljc actor boundary** です。
アプリではありません。ここに在るのは「どの cell が、どの gate を満たしたときに、
どの collection へ何を書くつもりか」を組み立てる純関数 1 本と、その契約テストだけです。

**アプリ（COFOG カタログ・級位/段位ラダー・フロントエンド）は隣の
[`cloud-itonami/app-society6`](../app-society6) に在ります。** 名前が
`society6` と `app-society6` で 1 語しか違わないので、境界をここで名乗っておきます。

| | `society6`（この repo） | `app-society6` |
|---|---|---|
| 中身 | actor boundary（`cell-plan` → MST put-record effect） | COFOG カタログ registry + appview（cljs）+ Worker entry |
| 言語 | cljc 1 本（7,059 byte） | TypeScript + ClojureScript |
| 実行 | **しない**。effect を*計画*するだけ | する（appview と Worker entry を持つ） |

**まず読むもの: [`docs/operator-quickstart.md`](docs/operator-quickstart.md)。**
JVM 無しで全部踏めます（`clojure` も要りません）。

## 中身

| path | 中身 |
|---|---|
| `src/society6/murakumo.cljc` | actor boundary。`cell-specs` に **11 cell**、`common-gates` に **7 gate** |
| `test/society6/murakumo_test.cljc` | 契約テスト。`cell-specs` を introspect するので cell を足しても書き換え不要 |
| `actor-manifest.jsonld` | **旧 TypeScript 実装の manifest**（下記） |
| `CLAUDE.md` | **旧 TypeScript 実装の説明**（下記） |
| `.well-known/did.json` | DID 文書の**古いローカル写し**（下記） |
| `tools/verify_docs_claims.cljs` | この README と quickstart が引用している数値を実ファイルと突き合わせる |

`murakumo.cljc` が依存するのは `clojure.string` だけです。だから nbb でも JVM でも
同じように動きます（quickstart で両方の実測値を並べています）。

### boundary が保証すること

`cell-plan` は gate が 1 つでも欠けていれば `:status :blocked` と
**空の `:effects`** を返します。満たされて初めて `:ready` と put-record effect が出ます。
判定は fail-closed です。`missing-gates` が常に「欠けていない」と答えるように
壊すと、161 assertion のうち **35 本**が落ちます（quickstart 4 節に実測）。

## identity と lexicon の宣言が食い違っている（実測 2026-09-02）

この repo の中で actor の identity と lexicon を宣言している場所は
`src/society6/murakumo.cljc` / `actor-manifest.jsonld` / `.well-known/did.json` の
**3 つ**で、そこに live の DID 文書を加えた 4 つが**互いに一致していません。** どれが正かはこの repo の中からは決められないので、
[`docs/adr/0001-record-the-three-way-identity-disagreement.md`](docs/adr/0001-record-the-three-way-identity-disagreement.md)
に**記録し、直さない**という決定を書きました。以下は所見であって修正ではありません。

### (1) source の DID は解決しない

| 宣言している場所 | DID |
|---|---|
| `src/society6/murakumo.cljc` の `actor-did` / `actor-manifest.jsonld` の `@id` | `did:web:society6.etzhayyim.com` |
| `.well-known/did.json` の `id` | `did:web:etzhayyim.com:actor:society6` |

did:web の解決先は `orgs/kotoba-lang/org-w3-did` の `did.core/did-web-url` で計算できます:

- `did:web:society6.etzhayyim.com` → `https://society6.etzhayyim.com/.well-known/did.json`
- `did:web:etzhayyim.com:actor:society6` → `https://etzhayyim.com/actor/society6/did.json`

実測（2026-09-02、public resolver 1.1.1.1 と 8.8.8.8 の両方）:
**`society6.etzhayyim.com` に A record はありません**（apex の `etzhayyim.com` は解決します）。
つまり `murakumo.cljc` が全ての put-record effect の `:actor` に刻んでいる DID は、
**現在どこからも解決できません。** 一方 `https://etzhayyim.com/actor/society6/did.json`
は HTTP 200 で live です。

この分岐は `c1b0e2c`（2026-07-02、"migrate did:web to etzhayyim.com scheme"）から続いています。
その commit が触ったのは `.well-known/did.json` の **4 行だけ**で、source と manifest は
旧 DID のまま残りました。

### (2) `.well-known/did.json` は live 文書の古い写しで、しかも置き場所が id と対応しない

repo の写しと live 文書は `@context`（ed25519-2020 と jws-2020）・PDS endpoint
（`pds.etzhayyim.com` と `pds.aozora.app`）・service の顔ぶれが違います。
さらに、この文書の `id` は path 形の DID なのに、ファイルは `.well-known/did.json`
——**subdomain 形の DID の置き場所**——に在ります。上の解決規則のとおり、
どの resolver もこの id をこのパスからは取りに来ません。

### (3) collection NSID は scaffold と manifest で 1 つも重ならない

- scaffold（`(collection "s6rank")` → `com.etzhayyim.society6.s6rank`）: **11 本**
- manifest（`com.etzhayyim.apps.society6.s6Rank` など）: **8 本**
- **共通部分は 0 本。** `apps.` の有無と大小文字の 2 点が違います。

**live の DID 文書は `"primaryLexicon": "com.etzhayyim.society6"` と書いており、
これは scaffold の prefix と一致し manifest とは一致しません。** manifest が
旧 TypeScript 実装のものであることの傍証ですが、断定はしません（ADR 0001）。

### (4) 参照されているが存在しないファイルが 4 つ

`NOTICE` → `CHARTER-RIDER.md` / `CLAUDE.md` → `wasm/society6-ui-s6c9m2q1/` /
`actor-manifest.jsonld` の `complianceDocs` → `90-docs/rules/...` と `90-docs/platform/...`。
いずれも旧 monorepo のパスで、この repo には在りません。

## この repo を読むときの優先順位

1. **`src/society6/murakumo.cljc`** — ここだけが実行される（正確には、実行可能な計画を作る）
2. **`docs/adr/`** — なぜ食い違いを直していないか
3. `actor-manifest.jsonld` / `CLAUDE.md` / `MIGRATION-TODO.md` — 旧 TypeScript 実装の記述。
   **現在のコードの仕様として読まない**

## ライセンス

Apache License 2.0 + etzhayyim Charter Compliance Rider v3.1（`NOTICE`）。
Rider 本文 `CHARTER-RIDER.md` はこの repo には在りません（上記 (4)）。

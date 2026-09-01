# ADR 0001 — actor identity の食い違いを記録し、直さない

- **status**: accepted
- **date**: 2026-09-02
- **scope**: `cloud-itonami/society6`

## 文脈

この repo は actor identity と lexicon prefix を 4 箇所で宣言していて、互いに
一致していない。実測（2026-09-02）:

| 場所 | DID | lexicon prefix |
|---|---|---|
| `src/society6/murakumo.cljc` | `did:web:society6.etzhayyim.com` | `com.etzhayyim.society6.*`（11 本） |
| `actor-manifest.jsonld` | `did:web:society6.etzhayyim.com` | `com.etzhayyim.apps.society6.*`（8 本） |
| `.well-known/did.json`（ローカルの写し） | `did:web:etzhayyim.com:actor:society6` | — |
| **live 文書**（`https://etzhayyim.com/actor/society6/did.json`、HTTP 200） | `did:web:etzhayyim.com:actor:society6` | `com.etzhayyim.society6`（`_meta.primaryLexicon`） |

scaffold の 11 本と manifest の 8 本の**共通部分は 0 本**。

分岐の起点は `c1b0e2c`（2026-07-02、"migrate did:web to etzhayyim.com scheme"）で、
この commit が触ったのは `.well-known/did.json` の 4 行だけだった。source と manifest は
旧 DID のまま残された。

### 測って分かったこと

- `society6.etzhayyim.com` に **A record が無い**（1.1.1.1 / 8.8.8.8 の両方。apex の
  `etzhayyim.com` は解決する）。`did:web:society6.etzhayyim.com` の解決先は
  `https://society6.etzhayyim.com/.well-known/did.json` なので、**source が全ての
  put-record effect の `:actor` に刻んでいる DID は現在解決できない。**
- `.well-known/did.json` は live 文書と `@context`・PDS endpoint・service が違う古い写しで、
  かつ `id`（path 形）と置き場所（subdomain 形の場所）が対応していない。
- live 文書の `primaryLexicon` は **scaffold 側の prefix と一致する**。

## 決定

**食い違いを記録し、この repo の中では直さない。**

具体的には、次のいずれもこの ADR では行わない:

1. `murakumo.cljc` の `actor-did` を live DID へ書き換える
2. `collection` の prefix を manifest 側へ寄せる（またはその逆）
3. `.well-known/did.json` を live 文書の内容で上書きする、または削除する

## なぜ直さないか

- **DID は on-wire の identity で、`:actor` として全ての planned record に入る。**
  書き換えは「typo の修正」ではなく actor の名前の変更であり、この repo の中の情報
  だけでは、どちらが正で誰が既にどちらを見ているかを決められない。
- **証拠が一方向を指していない。** live 文書は path 形 DID を支持するが、隣の
  `app-society6` の README は「公開 deployment identity `society6.etzhayyim.com` は
  そのまま引き継いでいます」と書いている。DNS はその host を知らない。3 つ目の証拠が要る。
- **`.well-known/did.json` は他者データである。** 消せば `alsoKnownAs` に載っている
  `rad:` と GitHub の旧 identity が失われる。live 文書の `alsoKnownAs` は空なので、
  上書きは復元ではなく削除になる。
- **黙って直すと、記録されない identity 変更になる。** ここに書いて残せば、次に読む者は
  同じ調査を繰り返さずに済み、決めるべき人が決められる。

## 結果

- README と `docs/operator-quickstart.md` が食い違いを名指しし、`tools/verify_docs_claims.cljs`
  が「食い違いがまだこの形のままである」ことを検査する。**形が変われば検査が落ちる** ——
  つまり誰かが片側を直したら、この ADR が古くなったことが分かる。
- この repo は今のところ何も実行しないので、解決しない DID による実害は出ていない。
  **runtime を足す前にこの ADR を解消すること**（それが実害の発生点になる）。

## 解消の条件

次のどれかが揃えば、この ADR は superseded にして identity を 1 つに寄せる:

1. actor の所有者が正典の DID を指定する
2. `society6.etzhayyim.com` の DNS が復活し、`.well-known/did.json` が
   `did:web:society6.etzhayyim.com` を id として serve される（= subdomain 形が正）
3. `app-society6` の deployment identity が path 形へ移り、両者が一致する

## 関連

- `c1b0e2c` — 部分的に終わった did:web 移行（2026-07-02）
- `orgs/kotoba-lang/org-w3-did` — `did.core/did-web-url`（解決先の計算に使った）
- `cloud-itonami/app-society6` — アプリ側。deployment identity を主張している

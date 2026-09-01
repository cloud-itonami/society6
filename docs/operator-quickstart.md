# operator quickstart — society6

この repo で operator ができることは 4 つです: **契約テストを回す / boundary を
手で動かして計画を見る / テストが本当に落ちることを確かめる / この文書の主張が
まだ正しいか検査する。**

ここに書いてあるコマンドは全部 2026-09-02 に実際に踏んで、出力をそのまま貼って
あります。**踏めなかった手順は書いていません。**

前提は `nbb` だけです。**1〜2 節と 4〜6 節は JVM も `clojure` も要りません。**
3 節（repo が `deps.edn` で宣言している経路）だけが JVM を使います。

## 0. 取得

```bash
cd <superproject root>
west update --fetch smart society6
cd orgs/cloud-itonami/society6
```

## 1. 契約テストを回す（JVM 無し、約 2 秒）

`src/society6/murakumo.cljc` は `clojure.string` しか require しないので nbb で動きます。

```bash
cat > /tmp/s6-test.cljs <<'EOF'
(ns s6-test
  (:require [clojure.test :as t] [society6.murakumo-test]))
(t/run-tests 'society6.murakumo-test)
EOF
nbb --classpath "src:test" /tmp/s6-test.cljs
```

実測:

```
Testing society6.murakumo-test

Ran 9 tests containing 161 assertions.
0 failures, 0 errors.
```

## 2. boundary を手で動かす

gate を 1 つも渡さないと `:blocked`、7 つ全部渡すと `:ready` になります。

```bash
nbb --classpath src -e '
(ns x (:require [society6.murakumo :as m]))
(let [p (m/cell-plan :s6rank {})]
  (println "no attestations:" (:status p) "missing=" (count (:missing-gates p)) "effects=" (count (:effects p))))
(let [att (into {} (map (fn [g] [g true]) m/common-gates))
      p (m/cell-plan :s6rank {:attestations att :request-id "req-1"})]
  (println "all attested   :" (:status p) "effects=" (count (:effects p)))
  (println "collection     :" (:collection (first (:effects p))))
  (println "actor          :" (:actor (first (:effects p)))))'
```

実測:

```
no attestations: :blocked missing= 7 effects= 0
all attested   : :ready effects= 1
collection     : com.etzhayyim.society6.s6rank
actor          : did:web:society6.etzhayyim.com
```

最後の 2 行が README の「3 つの宣言が食い違っている」で言っている状態そのものです
——この `collection` は `actor-manifest.jsonld` が宣言する 8 本のどれとも一致せず、
この `actor` は現在 DNS で解決しません。**表示されているのは計画であって、
この repo は何も実行しません。**

## 3. repo が宣言している経路（JVM）

`deps.edn` の `:test` / `:lint` は JVM 経路です。1 節と同じ検査を、この workspace が
出荷時に使う runner で回します。

**`clojure` は resource-guard 経由で呼びます**（repo-wide mandatory。同時に 1 本しか
走らせない）。他セッションが lock を持っていると `exit 2` で即座に拒否されます
——待ちたい場合はそのまま再試行してください。

```bash
node <superproject root>/scripts/resource-guard.mjs run build -- clojure -M:test
node <superproject root>/scripts/resource-guard.mjs run build -- clojure -M:lint
```

実測（`-M:test`）——**1 節の nbb と件数が完全に一致します**:

```
Ran 9 tests containing 161 assertions.
0 failures, 0 errors.
```

実測（`-M:lint`）:

```
src/society6/murakumo.cljc:131:14: warning: unused binding input
linting took ...ms, errors: 0, warnings: 1
```

（所要時間はこのマシンの負荷で変わります。実測は 1,077ms と 3,276ms。
安定しているのは `errors: 0, warnings: 1` の側です。）

**この warning は既存で、この文書を書いた変更が持ち込んだものではありません。**
`:lint` は `--fail-level error` なので exit は 0 です。warning を 0 にするには
`records-for` の未使用 `input` を消す必要があり、それは source の変更なのでここでは
していません。

## 4. テストが本当に落ちることを確かめる

**緑を信じる前に、赤にできることを見てください。** 検査が起動していないときと
検査が通ったときは、出力が同じ「異常なし」になります。

repo を汚さないよう写しの上で壊します:

```bash
rm -rf /tmp/s6-mut && cp -R . /tmp/s6-mut && rm -rf /tmp/s6-mut/.git
# missing-gates が常に「欠けていない」と答えるようにする = fail-closed を壊す
perl -0pi -e 's/\(remove #\(boolean \(gate-value attestations %\)\)\)/(remove (fn [_] true))/' \
  /tmp/s6-mut/src/society6/murakumo.cljc
diff -q src/society6/murakumo.cljc /tmp/s6-mut/src/society6/murakumo.cljc \
  || echo "mutation applied"      # 適用されていないのに緑を見て安心しないため
(cd /tmp/s6-mut && nbb --classpath "src:test" /tmp/s6-test.cljs)
rm -rf /tmp/s6-mut
```

実測——4 通り試して、いずれも無改変では 0 failures です:

| 壊した場所 | 壊し方 | 結果 |
|---|---|---|
| （無改変） | — | **0 failures** |
| `missing-gates` | 常に空を返す（gate を無効化） | 35 failures |
| `put-record-effect` | `:op` を `:mst/DELETE-record` に | 1 failures |
| `records-for` | `:scaffold` を `false` に | 11 failures |
| `safe-rkey` | blank のとき `""` を返す | 12 failures |

## 5. この文書の主張がまだ正しいか検査する

README と本ファイルは件数・prefix・DID 文字列を本文で引用しています。source が
動けば黙って嘘になるので、突き合わせる operator tool を置いてあります。

```bash
nbb tools/verify_docs_claims.cljs        # 既定はオフライン（network を使わない）
nbb tools/verify_docs_claims.cljs --network   # DNS と live DID 文書まで見る
```

exit は 3 値です:

| exit | 意味 |
|---|---|
| `0` | 全ての主張を突き合わせて一致した |
| `1` | 主張が実ファイルと食い違っている（文書か source のどちらかが古い） |
| `2` | **REFUSED** — 入力が読めない / 検査できた主張が床を下回った。**pass ではありません** |

実測（無改変、オフライン）:

```
SCANNED	14
VERIFIED	14
FAILED	0
```

赤くする方法（この tool 自身が discriminate することの確認）:

```bash
# README の cell 数を 11 → 12 に書き換えると落ちる
perl -0pi -e 's/\*\*11 cell\*\*/**12 cell**/' README.md
nbb tools/verify_docs_claims.cljs ; echo "exit=$?"
git checkout README.md
```

実測: `FAILED 1` / `exit=1`（`cell-count: doc says 12, repo has 11`）。

**「読めなかった」を「合格」と区別できることも確かめてあります。** 入力を 1 つ
奪う・主張の文言を regex が拾えないように書き換える・claim を 1 つ削って床を割る、
のいずれでも `exit=2` になります:

| 壊し方 | exit | 出力 |
|---|---|---|
| `README.md` を消す | 2 | `REFUSED cannot read README.md` |
| `.well-known/did.json` を消す | 2 | `REFUSED cannot read .well-known/did.json` |
| README の「**11 cell**」を「eleven cells」に | 2 | `REFUSED claim 'cell-count' not found in the document` |
| `test/` を壊して suite を走らなくする | 2 | `REFUSED could not run the contract suite under nbb` |
| tool から claim を 1 つ消す | 2 | `REFUSED only 13 claims were evaluated, floor is 14` |

**この 3 値化は後から入れたものです。** 最初の版は主張の導出を top-level `def` で
書いていたので、`refuse!` が namespace の読み込み中——`main` の try が存在する前——に
飛び、nbb がそのまま `exit 1` で終わっていました。`exit 1` は「主張が食い違っている」
の意味なので、**読めない入力が『実在しない drift』として報告されていました。**
`README.md` を消して `exit=1` が返り `REFUSED` 行が 1 行も出なかったのが実測です。
導出を `main` の中へ移して直しました（tool 冒頭のコメントに残してあります）。

## この repo に無いもの

- **runtime がありません。** `cell-plan` は effect を*返す*だけで、MST へ書く実装は
  ここに在りません。`:constitutionalStatus "attested-plan"` はそのことを言っています。
- **deploy がありません。** `wrangler` も CI workflow も在りません
  （この workspace の CI は murakumo fleet であって GitHub Actions ではありません）。
- **fleet gate にしていません。** 5 節の tool は `--network` で DNS と HTTP を使うので、
  ノードで回すと「測れなかった」が「合格」と同じ顔になります。手で回す operator tool の
  ままにしてあります。

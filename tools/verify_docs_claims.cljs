;; Verify that README.md and docs/operator-quickstart.md still describe this repo.
;;
;; Both documents quote counts, prefixes and DID strings out of the source. If the
;; source moves, the prose becomes false silently -- nothing in a test run reads it.
;; This tool re-derives every quoted value from the actual files and compares.
;;
;;   nbb tools/verify_docs_claims.cljs             # offline (default)
;;   nbb tools/verify_docs_claims.cljs --network   # also DNS + the live DID document
;;
;; Exit is three-valued on purpose. A checker that could not run must not return the
;; same value as a checker that ran and found nothing wrong:
;;
;;   0  every claim was re-derived and matched
;;   1  a claim disagrees with the repo (the prose or the source is stale)
;;   2  REFUSED -- an input was unreadable, or fewer claims survived than the floor.
;;      Not a pass.
;;
;; Deliberately NOT a fleet gate: --network needs DNS and HTTPS, and a node that
;; cannot reach them would report "measured nothing" in the same shape as "measured,
;; all fine". Operator tool, run by hand.

(ns verify-docs-claims
  (:require [clojure.string :as str]
            ["fs" :as fs]
            ["child_process" :as cp]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(def network? (boolean (some #{"--network"} argv)))

;; A claim that cannot be evaluated is not a claim that passed. Anything that
;; refuses raises, and `main` turns that into exit 2.
(defn refuse! [msg] (throw (ex-info msg {::refused true})))

(defn slurp! [path]
  (if (fs/existsSync path)
    (fs/readFileSync path "utf8")
    (refuse! (str "cannot read " path))))

(defn find1
  "The single capture of `re` in `text`, or refuse. Refusing (not returning nil)
   matters: a regex that stopped matching because the prose was reworded would
   otherwise silently drop that claim and shrink the scanned count."
  [label text re]
  (let [ms (re-seq re text)]
    (cond
      (empty? ms) (refuse! (str "claim '" label "' not found in the document"))
      :else (second (first ms)))))

(defn find-all [label text re]
  (let [ms (re-seq re text)]
    (if (empty? ms)
      (refuse! (str "claim '" label "' not found in the document"))
      (map second ms))))

(defn digits [s] (js/parseInt (str/replace (str s) #"[,\s]" "") 10))

;; ---------------------------------------------------------------- derive facts
;;
;; These are functions, not top-level defs, on purpose. As top-level defs the
;; `refuse!` calls below fire while the namespace is still loading -- before the
;; try/catch in `main` exists -- and nbb exits 1. Exit 1 means "a claim
;; disagrees", so an unreadable input would have been reported as a real drift
;; and sent the reader looking for a disagreement that was not there. Measured:
;; deleting README.md gave exit 1 and printed no REFUSED line at all.

(defn facts []
  (let [readme   (slurp! "README.md")
        quick    (slurp! "docs/operator-quickstart.md")
        src      (slurp! "src/society6/murakumo.cljc")
        manifest (slurp! "actor-manifest.jsonld")
        didjson  (slurp! ".well-known/did.json")
        prefix   (or (second (re-find #"\(str \"(com\.[a-z0-9.]*?)\" name\)\)" src))
                     (refuse! "cannot read the collection prefix out of the source"))
        gates    (let [block (second (re-find #"\(def common-gates\s*\[([\s\S]*?)\]\)" src))]
                   (if block (count (re-seq #":[a-z0-9-]+" block))
                       (refuse! "cannot read common-gates")))]
    {:readme readme
     :quick quick
     :src src
     :src-bytes (.-size (fs/statSync "src/society6/murakumo.cljc"))
     :cell-count (count (re-seq #"(?m)^\s{2}:[a-z0-9-]+ \{:legacy-cell" src))
     :gate-count gates
     :scaffold-prefix prefix
     :scaffold-nsids (into #{} (map #(str prefix %)
                                    (map second (re-seq #"\(collection \"([^\"]*)\"\)" src))))
     :manifest-nsids (into #{} (map #(str/replace % "\"" "")
                                    (re-seq #"\"com\.etzhayyim\.[A-Za-z0-9.\-]*\"" manifest)))
     :source-did (or (second (re-find #"\(def actor-did\s*\"([^\"]+)\"\)" src))
                     (refuse! "cannot read actor-did"))
     :didjson-did (or (second (re-find #"\"id\"\s*:\s*\"(did:web:[^\"#]+)\"" didjson))
                      (refuse! "cannot read the id out of .well-known/did.json"))}))

(def referenced-absent
  ["CHARTER-RIDER.md"
   "wasm/society6-ui-s6c9m2q1"
   "90-docs/rules/compliance/per-did-kyumei-shinka-autonomy.md"
   "90-docs/platform/260403-live-data-kyumei-shinka-consolidated.md"])

(defn run-suite
  "Run the contract suite under nbb and return {:tests n :assertions n}."
  []
  (let [entry "/tmp/s6-verify-suite.cljs"]
    (fs/writeFileSync entry
      "(ns s6-verify-suite (:require [clojure.test :as t] [society6.murakumo-test]))\n(t/run-tests 'society6.murakumo-test)\n")
    (let [out (try
                (str (cp/execSync (str "nbb --classpath \"src:test\" " entry)
                                  #js {:encoding "utf8" :stdio "pipe"}))
                (catch :default e
                  (refuse! (str "could not run the contract suite under nbb: "
                                (.-message e)))))
          m (re-find #"Ran (\d+) tests containing (\d+) assertions" out)]
      (if m
        {:tests (digits (nth m 1)) :assertions (digits (nth m 2))}
        (refuse! "the suite ran but printed no summary line")))))

;; ------------------------------------------------------------------- claims

(defn offline-claims [f suite]
  (let [{:keys [readme quick src-bytes cell-count gate-count scaffold-prefix
                scaffold-nsids manifest-nsids source-did didjson-did]} f]
    [{:id "cell-count"
      :doc (digits (find1 "cell-count" readme #"\*\*(\d+) cell\*\*"))
      :repo cell-count}
     {:id "gate-count"
      :doc (digits (find1 "gate-count" readme #"\*\*(\d+) gate\*\*"))
      :repo gate-count}
     {:id "src-bytes"
      :doc (digits (find1 "src-bytes" readme #"cljc 1 本（([\d,]+) byte）"))
      :repo src-bytes}
     {:id "scaffold-nsid-count"
      :doc (digits (find1 "scaffold-nsid-count" readme #"(?m)^- scaffold（[^）]*）: \*\*(\d+) 本\*\*"))
      :repo (count scaffold-nsids)}
     {:id "manifest-nsid-count"
      :doc (digits (find1 "manifest-nsid-count" readme #"(?m)^- manifest（[^）]*）: \*\*(\d+) 本\*\*"))
      :repo (count manifest-nsids)}
     {:id "nsid-intersection"
      :doc (digits (find1 "nsid-intersection" readme #"\*\*共通部分は (\d+) 本。\*\*"))
      :repo (count (filter scaffold-nsids manifest-nsids))}
     {:id "scaffold-prefix"
      :doc (find1 "scaffold-prefix" readme #"→ `(com\.etzhayyim\.society6\.)s6rank`")
      :repo scaffold-prefix}
     {:id "source-did"
      :doc (if (str/includes? readme source-did) source-did "<not quoted in README>")
      :repo source-did}
     {:id "didjson-did"
      :doc (if (str/includes? readme didjson-did) didjson-did "<not quoted in README>")
      :repo didjson-did}
     {:id "dids-still-disagree"
      :doc "differ"
      :repo (if (= source-did didjson-did) "same" "differ")}
     {:id "didjson-is-path-form-at-well-known"
      :doc "path-form-at-well-known"
      :repo (if (and (str/includes? didjson-did ":actor:")
                     (fs/existsSync ".well-known/did.json"))
              "path-form-at-well-known"
              "changed")}
     {:id "referenced-absent-count"
      :doc (digits (find1 "referenced-absent-count" readme #"存在しないファイルが (\d+) つ"))
      :repo (count (remove fs/existsSync referenced-absent))}
     {:id "test-count"
      :doc (let [vs (map digits (find-all "test-count" quick #"Ran (\d+) tests containing"))]
             (if (apply = vs) (first vs) (refuse! "the quickstart quotes two different test counts")))
      :repo (:tests suite)}
     {:id "assertion-count"
      :doc (let [vs (map digits (find-all "assertion-count" quick #"containing (\d+) assertions"))]
             (if (apply = vs) (first vs) (refuse! "the quickstart quotes two different assertion counts")))
      :repo (:assertions suite)}]))

(defn sh [cmd]
  (try (str/trim (str (cp/execSync cmd #js {:encoding "utf8" :stdio "pipe"})))
       (catch :default _ nil)))

(defn network-claims [f]
  (let [a-record (sh "dig +short @1.1.1.1 society6.etzhayyim.com A")
        apex     (sh "dig +short @1.1.1.1 etzhayyim.com A")
        live     (sh "curl -sS --max-time 20 https://etzhayyim.com/actor/society6/did.json")]
    (when (nil? apex) (refuse! "DNS is unreachable from here -- cannot evaluate the network claims"))
    (when (str/blank? apex) (refuse! "the apex etzhayyim.com did not resolve -- DNS answers here are not trustworthy"))
    (when (str/blank? (str live)) (refuse! "could not fetch the live DID document"))
    [{:id "subdomain-has-no-a-record"
      :doc "no-a-record"
      :repo (if (str/blank? (str a-record)) "no-a-record" (str "resolves: " a-record))}
     {:id "live-did-is-path-form"
      :doc (:didjson-did f)
      :repo (or (second (re-find #"\"id\"\s*:\s*\"(did:web:[^\"#]+)\"" live))
                "<no id in the live document>")}
     {:id "live-primary-lexicon-matches-scaffold"
      :doc (str/replace (:scaffold-prefix f) #"\.$" "")
      :repo (or (second (re-find #"\"primaryLexicon\"\s*:\s*\"([^\"]+)\"" live))
                "<absent>")}]))

;; --------------------------------------------------------------------- main

(def FLOOR 14) ; offline claims. Fewer than this means claims were silently dropped.

(defn -main []
  (let [f (facts)
        suite (run-suite)
        claims (into (vec (offline-claims f suite)) (if network? (network-claims f) []))
        scanned (count claims)
        failed (remove #(= (str (:doc %)) (str (:repo %))) claims)]
    (when (< scanned FLOOR)
      (refuse! (str "only " scanned " claims were evaluated, floor is " FLOOR)))
    (doseq [{:keys [id doc repo]} failed]
      (println (str "FAIL\t" id ": doc says " (pr-str doc) ", repo has " (pr-str repo))))
    (println (str "SCANNED\t" scanned))
    (println (str "VERIFIED\t" (- scanned (count failed))))
    (println (str "FAILED\t" (count failed)))
    (when-not network?
      (println "note\toffline mode: the DNS and live-document claims were NOT checked (--network)"))
    (.exit js/process (if (seq failed) 1 0))))

(try
  (-main)
  (catch :default e
    (println (str "REFUSED\t" (.-message e)))
    (println "note\trefusing to report a pass without having measured")
    (.exit js/process 2)))

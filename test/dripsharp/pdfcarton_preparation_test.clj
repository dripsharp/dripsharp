(ns dripsharp.pdfcarton-preparation-test
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.pdfcube.family-workflows :as family-workflows]
            [dripsharp.proof-resume :as proof-resume]
            [dripsharp.target-execution :as execution]))

(def profiles ["pdfcube-io" "pdfcube-fontbox" "pdfcube-xmpbox"
               "pdfcube-pdfbox" "pdfcube-preflight"])
(def assemblies ["DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts"
                 "DripSharp.PdfCarton.Xmp" "DripSharp.PdfCarton"
                 "DripSharp.PdfCarton.Preflight"])
(def family-verification
  {:generation {:generation-profile {:profile "pdfcube-preflight"}
                :dependency-emissions (mapv #(hash-map :profile %) (butlast profiles))}
   :public-surface {:assemblies (mapv #(hash-map :assembly %) assemblies)}})

(defn sync-fixture [verification exact?]
  (let [calls (atom [])
        result
        (execution/synchronize!
         {:target :pdfcube
          :prerequisites-fn (fn [_] (swap! calls conj :preflight))
          :verify-fn (fn [{:keys [profile]}]
                       (swap! calls conj [:generate-build-audit profile]) verification)
          :proof-fn (fn [_] (swap! calls conj :two-independent-package-builds) :proof)
          :test-suites-fn (fn [_] (swap! calls conj :shipped-tests))
          :staging-cleanup-fn (fn [_] (swap! calls conj :cleanup))
          :repository-proof-fn (fn [_]
                                 (swap! calls conj :exact-commit-check)
                                 (if exact? {:repository-commit "exact"}
                                     (throw (ex-info "stale" {:reason :stale-generated-product-commit}))))
          :synchronize-fn (fn [_] (swap! calls conj :sync) {:changes []})})]
    {:calls @calls :result result}))

(deftest one-complete-family-generation-retains-every-audit-and-policy-stage
  (doseq [change [:production :version-only :unchanged :metadata]
          :let [exact? (contains? #{:unchanged :metadata} change)
                {:keys [calls result]} (sync-fixture family-verification exact?)]]
    (is (= 1 (get-in result [:precommit-proof :generation-invocations])))
    (is (= profiles (mapv :profile (get-in result [:precommit-proof :profiles]))))
    (is (= 1 (count (filter vector? calls))))
    (is (= (if exact? 2 1) (count (filter #{:shipped-tests} calls)))))
  ;; Each older per-profile call walked its complete dependency closure.
  (let [old-closures [["io"] ["io" "fonts"] ["xmp"]
                      ["io" "fonts" "pdf"] ["io" "fonts" "xmp" "pdf" "preflight"]]]
    (is (= 12 (count (mapcat identity old-closures))))
    (is (= 5 (count profiles)))))

(deftest incomplete-or-duplicate-family-evidence-fails-closed
  (doseq [bad [(update-in family-verification [:generation :dependency-emissions] pop)
               (update-in family-verification [:generation :dependency-emissions] conj {:profile "pdfcube-io"})
               (update-in family-verification [:public-surface :assemblies] pop)
               (assoc-in family-verification [:public-surface :assemblies 0 :assembly] "unexpected")]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"every declared profile exactly once"
                          (sync-fixture bad false)))))

(deftest exact-pass-rejects-managed-drift
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"did not synchronize cleanly"
       (with-redefs [execution/synchronize! execution/synchronize!]
         (execution/synchronize!
          {:target :pdfcube :prerequisites-fn (constantly :ready)
           :verify-fn (constantly family-verification)
           :proof-fn (constantly :proof) :test-suites-fn (constantly :tests)
           :staging-cleanup-fn (constantly :clean)
           :repository-proof-fn (constantly {:repository-commit "exact"})
           :synchronize-fn (constantly {:changes ["README.md"]})})))))

(defn postcommit-options [calls fail-at]
  (let [stage (fn [id result]
                (fn [& _]
                  (swap! calls conj id)
                  (when (= id fail-at)
                    (throw (ex-info "stage failed" {:stage id})))
                  result))]
    {:target :pdfcube :post-commit? true
     :prerequisites-fn (stage :preflight {:repository {:base-commit "exact"}})
     :verify-fn (fn [_] (throw (ex-info "Redundant prefix ran" {})))
     :proof-fn (stage :full-package-and-behavior-proof :proof)
     :test-suites-fn (stage :shipped-tests :tests)
     :staging-cleanup-fn (stage :cleanup :clean)
     :repository-proof-fn (stage :exact-commit-check {:repository-commit "exact"})
     :synchronize-fn (stage :sync {:changes []})}))

(def postcommit-stages
  [:preflight :full-package-and-behavior-proof :shipped-tests
   :cleanup :exact-commit-check :sync])

(deftest explicit-postcommit-never-reuses-staging-or-skips-final-proof
  ;; No result cache or source-diff shortcut: even an unchanged retry runs the
  ;; complete package proof, and version/metadata edits cannot reuse old stamps.
  (let [calls (atom [])]
    (doseq [change [:production :version-only :unchanged-retry :metadata]
            :let [result (execution/synchronize! (postcommit-options calls nil))]]
      (is (= :exact-commit-proof (:mode result)) (name change))
      (is (nil? (:precommit-proof result)))
      (is (= :proof (:proof result)))
      (is (= :tests (:test-suites result))))
    (is (= (vec (mapcat identity (repeat 4 postcommit-stages))) @calls))))

(deftest explicit-postcommit-failures-stop-before-later-stages
  (doseq [[index stage] (map-indexed vector postcommit-stages)]
    (let [calls (atom [])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"stage failed"
                            (execution/synchronize! (postcommit-options calls stage))))
      (is (= (subvec postcommit-stages 0 (inc index)) @calls)))))

(deftest explicit-postcommit-rejects-stale-generated-bytes-and-managed-drift
  (let [calls (atom [])]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"changed the committed generated bytes"
         (execution/synchronize!
          (assoc (postcommit-options calls nil)
                 :repository-proof-fn
                 (fn [_] (throw (ex-info "stale" {:reason :stale-generated-product-commit})))))))
    (is (not-any? #{:sync} @calls)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"did not synchronize cleanly"
       (execution/synchronize!
        (assoc (postcommit-options (atom []) nil)
               :synchronize-fn (constantly {:changes ["README.md"]}))))))

(deftest explicit-postcommit-is-limited-to-approved-product
  (let [calls (atom [])]
    (doseq [target [:pkl :sqltrellis]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"only for PdfCarton"
           (execution/synchronize! (assoc (postcommit-options calls nil) :target target)))))
    (is (empty? @calls))))

(deftest explicit-postcommit-rejects-commit-change-even-with-identical-managed-bytes
  (let [calls (atom [])]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"commit changed during post-commit proof"
         (execution/synchronize!
          (assoc (postcommit-options calls nil)
                 :repository-proof-fn (constantly {:repository-commit "new-metadata-only-commit"})))))
    (is (not-any? #{:sync} @calls))))

(deftest explicit-postcommit-routes-through-required-family-proof-under-generation-lock
  (let [calls (atom [])
        options (-> (postcommit-options calls nil)
                    (dissoc :proof-fn)
                    (assoc :verify-fn
                           (fn [{:keys [profile]}]
                             (is (= "pdfcube-preflight" profile))
                             (is proof-resume/*generation-lock*)
                             (swap! calls conj :clean-family-build)
                             family-verification)
                           :pack-fn
                           (fn [{:keys [verify-fn repository-proof-fn]}]
                             ;; Exercise the real target routing with a bounded
                             ;; package-stage fixture. Packaging's own tests
                             ;; assert its independent build/comparison loop.
                             (dotimes [_ 2]
                               (verify-fn {:profile "pdfcube-preflight"})
                               (repository-proof-fn))
                             :package-proof)
                           :test-suites-fn
                           (fn [_]
                             (is proof-resume/*generation-lock*)
                             (swap! calls conj :shipped-tests)
                             :tests)
                           :synchronize-fn
                           (fn [_]
                             (is proof-resume/*generation-lock*)
                             (swap! calls conj :sync)
                             {:changes []})))]
    (with-redefs [family-workflows/verify!
                  (fn [{:keys [pack-fn]}]
                    (is proof-resume/*generation-lock*)
                    (swap! calls conj :family-proof)
                    (is (= :package-proof (pack-fn {:profile "pdfcube-preflight"})))
                    (swap! calls conj :family-validators)
                    :verified)]
      (is (= :exact-commit-proof (:mode (execution/synchronize! options)))))
    (is (= [:preflight :family-proof
            :clean-family-build :exact-commit-check
            :clean-family-build :exact-commit-check
            :family-validators :shipped-tests :cleanup :exact-commit-check :sync]
           @calls))))

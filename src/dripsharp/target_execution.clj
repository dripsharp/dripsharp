(ns dripsharp.target-execution
  "Metadata-driven execution of one preflighted target-directory contract."
  (:refer-clojure :exclude [run!])
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.alpha-release :as alpha-release]
            [dripsharp.baseline :as baseline]
            [dripsharp.compiler :as compiler]
            [dripsharp.consumer-tests :as consumer-tests]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.java-project :as java-project]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.product-repository :as product-repository]
            [dripsharp.product-staging :as product-staging]
            [dripsharp.target-directory :as target-directory]))

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind :invalid-target-execution))))

(defn- target-selection!
  [target]
  (or target
      (fail! "Target execution requires an explicit target selection"
             {:reason :missing-target-selection})))

(defn- profile-selection!
  [profile]
  (or profile
      (fail! "Target execution requires an explicit profile selection"
             {:reason :missing-profile-selection})))

(defn- target-relative
  [target value]
  (str "targets/" (name target) "/" value))

(defn- execution-profile
  [target configuration]
  (cond-> configuration
    (and (string? (:maven-build-input-contract configuration))
         (not (str/includes? (:maven-build-input-contract configuration) "/")))
    (update :maven-build-input-contract #(target-relative target %))))

(defn- execution-destination
  [target destination]
  (update destination :runtime-sources
          (fn [sources]
            (when sources
              (mapv #(target-relative target %) sources)))))

(defn- resource-notice-attribution
  [baseline-record legal-set-keys]
  {:legal-sets legal-set-keys
   :package-paths
   (->> legal-set-keys
        (mapcat #(get-in baseline-record [:legal-sets %]))
        (filter #(= :notice (:kind %)))
        (map :package-path)
        distinct
        vec)})

(defn- overlay-registries
  [profile-record]
  (into {}
        (map (fn [[id record]] [id (:registry record)]))
        (:mapping-overlays profile-record)))

(defn- compose-rule-bundle
  [bundle registries target]
  (if (empty? registries)
    bundle
    (let [base-fn
          (get-in bundle
                  [:rules :resolved-mappings
                   :declarative-mapping-registries])
          create-template
          (get-in bundle [:rules :structural-declarations
                          :create-template])]
      (-> bundle
          (assoc-in
           [:rules :resolved-mappings :declarative-mapping-registries]
           (fn [context]
             (let [base (base-fn context)
                   conflicts (set/intersection (set (keys base))
                                               (set (keys registries)))]
               (when (seq conflicts)
                 (fail! "Target mapping overlays collide with bundle registries"
                        {:target target
                         :registries (vec (sort conflicts))}))
               (merge base registries))))
          (assoc-in
           [:rules :structural-declarations :create-template]
           (fn [resolved-model options]
             (assoc (create-template resolved-model options)
                    :target-mapping-registries
                    (mapv val (sort-by (comp str key) registries)))))))))

(defn- plan
  [{:keys [workspace-root target profile read-target-fn]
    :or {read-target-fn target-directory/read-target}}]
  (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
        target-contract (read-target-fn root (target-selection! target))
        target (:target target-contract)
        profiles (:profiles target-contract)
        profile-name (when profile
                       (if (keyword? profile) (name profile) (str profile)))]
    (when (and profile-name (not (contains? profiles profile-name)))
      (fail! "Target has no such generation profile"
             {:target target
              :profile profile-name
              :available (vec (sort (keys profiles)))}))
    (let [baseline-record (get-in target-contract [:baseline :record])
          records {target baseline-record}
          prepared-profiles
          (binding [baseline/*target-records* records]
            (into
             {}
             (map
              (fn [[id record]]
                [id
                 (execution-profile
                  target
                  (baseline/hydrate-profile
                   root (:configuration record)))]))
             profiles))
          prepared-destinations
          (binding [baseline/*target-records* records]
            (reduce
             (fn [result [_profile-id
                          {:keys [destination authorship
                                  resource-notice-legal-sets]}]]
               (let [path (get-in destination [:descriptor :path])
                     configuration
                     (assoc
                      (execution-destination
                       target
                       (baseline/hydrate-destination
                        root (:configuration destination)))
                      :authorship authorship
                      :resource-notice-attribution
                      (resource-notice-attribution
                       baseline-record resource-notice-legal-sets))]
                 (if-let [existing (get result path)]
                   (if (= existing configuration)
                     result
                     (fail! "Profiles sharing a destination have different authorship contracts"
                            {:target target :destination path}))
                   (assoc result path configuration))))
             {}
             (:profiles target-contract)))]
      {:workspace-root root
       :target target
       :profile profile-name
       :contract target-contract
       :baseline-records records
       :profiles prepared-profiles
       :destinations prepared-destinations
       :read-profile
       (fn [_root selector]
         (or (get prepared-profiles selector)
             (fail! "Target profile dependency is not declared"
                    {:target target :profile selector
                     :available (vec (sort (keys prepared-profiles)))})))
       :read-destination
       (fn [_root selector]
         (or (get prepared-destinations selector)
             (fail! "Target destination is not declared"
                    {:target target :destination selector
                     :available (vec (sort (keys prepared-destinations)))})))
       :profile-records profiles})))

(defn- stage-options
  [options]
  (dissoc options
          :target :validation :read-target-fn
          :generate-fn :verify-fn :pack-fn :package-fn
          :differential-fn :proof-fn :synchronize-fn :prepare-fn
          :test-suites-fn :consumer-tests-fn :staging-cleanup-fn
          :repository-proof-fn
          :release-fn :build-fn :framework-assemblies :inventory
          :branch :commit-message :pull-request-title :pull-request-body
          :authorized-tag :product-commit :platform-ids :output-root))

(defn- generate-loaded!
  [execution options generate-fn]
  (let [profile (profile-selection! (:profile execution))
        profile-record (get-in execution [:profile-records profile])
        registries (overlay-registries profile-record)
        target (:target execution)
        base-emit-project-fn
        (or (:emit-project-fn options) java-project/emit-project!)
        emit-project-fn
        (fn [emit-options]
          (let [bundle
                (compose-rule-bundle
                 (:rule-bundle emit-options) registries target)]
            (base-emit-project-fn
             (assoc emit-options :rule-bundle bundle))))]
    (binding [baseline/*target-records* (:baseline-records execution)]
      (let [generation
            (generate-fn
             (merge
              (stage-options options)
              {:workspace-root (:workspace-root execution)
               :profile profile
               :read-profile-fn (:read-profile execution)
               :read-destination-fn (:read-destination execution)}
              {:emit-project-fn emit-project-fn}))]
        (if (= :generated-repository
               (get-in execution [:contract :publication :kind]))
          (let [staging
                (product-staging/emit!
                 {:workspace-root (:workspace-root execution)
                  :target-contract (:contract execution)
                  :generation generation})
                test-suites
                (consumer-tests/emit!
                 {:workspace-root (:workspace-root execution)
                  :target-contract (:contract execution)})]
            (assoc generation
                   :product-staging staging
                   :test-suites test-suites
                   :consumer-tests test-suites))
          generation)))))

(defn generate!
  [{:keys [generate-fn] :as options}]
  (let [execution (plan (update options :profile profile-selection!))]
    (generate-loaded! execution options (or generate-fn harness/generate!))))

(defn- verify-loaded!
  [execution options verify-fn generate-fn]
  (binding [baseline/*target-records* (:baseline-records execution)]
    (verify-fn
     (merge
      (stage-options options)
      {:workspace-root (:workspace-root execution)
       :profile (profile-selection! (:profile execution))
       :generate-fn
       #(generate-loaded! execution % generate-fn)}))))

(defn verify!
  [{:keys [verify-fn generate-fn] :as options}]
  (let [execution (plan (update options :profile profile-selection!))]
    (verify-loaded! execution options
                    (or verify-fn compiler/verify-clean-build!)
                    (or generate-fn harness/generate!))))

(defn- pack-loaded!
  [execution options pack-fn verify-fn generate-fn]
  (binding [baseline/*target-records* (:baseline-records execution)]
    (pack-fn
     (merge
      (stage-options options)
      {:workspace-root (:workspace-root execution)
       :profile (profile-selection! (:profile execution))
       :repository-proof-fn
       (when (= :generated-repository
                (get-in execution [:contract :publication :kind]))
         #(product-repository/verify-synchronized!
           {:workspace-root (:workspace-root execution)
            :target-contract (:contract execution)}))
       :verify-fn
       #(verify-loaded! execution % verify-fn generate-fn)}))))

(defn pack!
  [{:keys [pack-fn verify-fn generate-fn] :as options}]
  (let [execution (plan (update options :profile profile-selection!))]
    (pack-loaded! execution options
                  (or pack-fn packaging/pack-verified-profile!)
                  (or verify-fn compiler/verify-clean-build!)
                  (or generate-fn harness/generate!))))

(defn- package-loaded!
  [execution options package-fn pack-fn verify-fn generate-fn]
  (binding [baseline/*target-records* (:baseline-records execution)]
    (package-fn
     (merge
      (stage-options options)
      {:workspace-root (:workspace-root execution)
       :profile (profile-selection! (:profile execution))
       :target-contract (:contract execution)
       :verify-fn
       #(verify-loaded! execution % verify-fn generate-fn)
       :pack-fn
       #(pack-loaded! execution % pack-fn verify-fn generate-fn)}))))

(defn package!
  [{:keys [package-fn pack-fn verify-fn generate-fn] :as options}]
  (let [execution (plan (update options :profile profile-selection!))]
    (package-loaded! execution options
                     (or package-fn packaging/verify-package-consumption!)
                     (or pack-fn packaging/pack-verified-profile!)
                     (or verify-fn compiler/verify-clean-build!)
                     (or generate-fn harness/generate!))))

(defn execution-differential-contract
  "Rebases target-relative validation paths for the shared differential runner."
  [target contract]
  (let [relocate
        (fn [value]
          (if (and (string? value)
                   (str/starts-with? value "validation/"))
            (target-relative target value)
            value))]
    (-> contract
        (update-in [:runner :context]
                   #(into {} (map (fn [[id path]] [id (relocate path)])) %))
        (update-in [:runner :oracle :source] relocate)
        (update-in [:runner :probe :source] relocate))))

(defn- package-for-profile
  [execution options profile]
  (let [profile-execution (assoc execution :profile profile)]
    (package-loaded!
     profile-execution (assoc options :profile profile)
     (or (:package-fn options) packaging/verify-package-consumption!)
     (or (:pack-fn options) packaging/pack-verified-profile!)
     (or (:verify-fn options) compiler/verify-clean-build!)
     (or (:generate-fn options) harness/generate!))))

(defn- run-validation!
  [execution options validation]
  (let [contract (:contract validation)
        profile (or (:profile contract)
                    (get-in contract [:runner :profile]))
        package-fn
        (fn [package-options]
          (package-for-profile execution
                               (merge options package-options)
                               (:profile package-options)))]
    (binding [baseline/*target-records* (:baseline-records execution)]
      (case (get-in validation [:descriptor :kind])
        :differential
        ((or (:differential-fn options) differential/run!)
         {:workspace-root (:workspace-root execution)
          :contract (execution-differential-contract
                     (:target execution) contract)
          :package-fn package-fn})

        :custom
        ((:runner validation)
         {:workspace-root (:workspace-root execution)
          :target-contract (:contract execution)
          :validation-contract contract
          :profile profile
          :package-fn package-fn
          :core-package-fn package-fn
          :pack-fn
          (fn [pack-options]
            (pack-loaded!
             (assoc execution :profile (:profile pack-options))
             (merge options pack-options)
             (or (:pack-fn options) packaging/pack-verified-profile!)
             (or (:verify-fn options) compiler/verify-clean-build!)
             (or (:generate-fn options) harness/generate!)))
          :verify-clean-build-fn
          (fn [verify-options]
            (verify-loaded!
             (assoc execution :profile (:profile verify-options))
             (merge options verify-options)
             (or (:verify-fn options) compiler/verify-clean-build!)
             (or (:generate-fn options) harness/generate!)))})

        (fail! "Target validation has an unsupported kind"
               {:target (:target execution)
                :validation (get-in validation [:descriptor :id])})))))

(defn differential!
  [{:keys [validation] :as options}]
  (let [execution (plan (assoc options :profile nil))
        validations (get-in execution [:contract :validation-contracts])
        selected
        (if validation
          [(or (get validations (keyword validation))
               (fail! "Target has no such validation contract"
                      {:target (:target execution)
                       :validation validation
                       :available (vec (sort (keys validations)))}))]
          (mapv validations
                (map :id
                     (get-in execution
                             [:contract :manifest
                              :validation-contracts]))))]
    (mapv #(run-validation! execution options %) selected)))

(defn- proof-loaded!
  [execution options]
  (let [validations (get-in execution [:contract :validation-contracts])
        ladders (get-in execution [:contract :proof :ladders])]
    (mapv
     (fn [{:keys [id kind validation-contracts resource-class runner]}]
       {:id id
        :resource-class resource-class
        :result
        (case kind
          :target-validations
          (mapv
           (fn [validation-id]
             (run-validation!
              execution options
              (or (get validations validation-id)
                  (fail! "Proof ladder selects an unavailable validation"
                         {:target (:target execution)
                          :ladder id
                          :validation validation-id}))))
           validation-contracts)

          :custom
          (runner
           {:workspace-root (:workspace-root execution)
            :target-contract (:contract execution)
            :pack-fn
            (fn [pack-options]
              (let [profile (profile-selection! (:profile pack-options))]
                (pack-loaded!
                 (assoc execution :profile profile)
                 (merge options pack-options)
                 (or (:pack-fn options) packaging/pack-verified-profile!)
                 (or (:verify-fn options) compiler/verify-clean-build!)
                 (or (:generate-fn options) harness/generate!))))})

          (fail! "Proof ladder has an unsupported kind"
                 {:target (:target execution) :ladder id :kind kind}))})
     ladders)))

(defn proof!
  "Runs every required proof ladder declared by one preflighted target.
  Ladders are mandatory, exhaustive over target profiles and validations, and
  carry their CI resource class in the target contract."
  [options]
  (proof-loaded! (plan (assoc options :profile nil)) options))

(defn- generated-publication!
  [execution]
  (let [publication (get-in execution [:contract :publication])]
    (when-not (= :generated-repository (:kind publication))
      (fail! "Target is conformance-only and cannot be synchronized"
             {:target (:target execution)
              :reason :conformance-only-target
              :publication-kind (:kind publication)}))
    publication))

(defn- publication-proof!
  [execution options]
  (if-let [proof-fn (:proof-fn options)]
    (proof-fn {:workspace-root (:workspace-root execution)
               :target (:target execution)})
    (proof-loaded! execution options)))

(defn- precommit-publication-proof!
  [execution options]
  {:kind :pre-commit-generated-product
   :frameworks (get-in execution [:contract :frameworks])
   :profiles
   (mapv
    (fn [profile]
      {:profile profile
       :verification
       (verify-loaded!
        (assoc execution :profile profile)
        (assoc options :profile profile)
        (or (:verify-fn options) compiler/verify-clean-build!)
        (or (:generate-fn options) harness/generate!))})
    (map :id (get-in execution [:contract :manifest :profiles])))})

(defn- generated-test-suites!
  [execution options]
  ((or (:test-suites-fn options)
       (:consumer-tests-fn options)
       consumer-tests/verify!)
   {:workspace-root (:workspace-root execution)
    :target-contract (:contract execution)
    :run-command! (:run-command! options)}))

(defn- clean-publication-staging!
  [execution options]
  ((or (:staging-cleanup-fn options)
       product-staging/clean-build-artifacts!)
   {:workspace-root (:workspace-root execution)
    :target-contract (:contract execution)}))

(defn- repository-proof-state!
  [execution options]
  (let [proof-fn
        (or (:repository-proof-fn options)
            product-repository/verify-synchronized!)
        proof-options
        (cond-> {:workspace-root (:workspace-root execution)
                 :target-contract (:contract execution)}
          (:run-command! options)
          (assoc :run-command! (:run-command! options)))]
    (try
      {:state :exact
       :proof (proof-fn proof-options)}
      (catch clojure.lang.ExceptionInfo error
        (if (= :stale-generated-product-commit (:reason (ex-data error)))
          {:state :stale}
          (throw error))))))

(defn synchronize!
  "Clean-generates every product profile and verifies generated tests before
  synchronizing declared managed paths. When staged bytes already equal the
  exact clean product commit, also runs the complete package, consumer, and
  behavior proof before confirming synchronization is a no-op."
  [options]
  (let [execution (plan (assoc options :profile nil))
        _ (generated-publication! execution)
        precommit-proof (precommit-publication-proof! execution options)
        precommit-test-suites (generated-test-suites! execution options)
        precommit-cleanup (clean-publication-staging! execution options)
        initial-repository (repository-proof-state! execution options)
        exact-commit? (= :exact (:state initial-repository))
        proof (if exact-commit?
                (publication-proof! execution options)
                precommit-proof)
        test-suite-verification
        (if exact-commit?
          (generated-test-suites! execution options)
          precommit-test-suites)
        staging-cleanup
        (if exact-commit?
          (clean-publication-staging! execution options)
          precommit-cleanup)
        repository-proof
        (when exact-commit?
          (let [post-proof (repository-proof-state! execution options)]
            (when-not (= :exact (:state post-proof))
              (fail! "Complete product proof changed the committed generated bytes"
                     {:target (:target execution)
                      :reason :stale-generated-product-commit}))
            (:proof post-proof)))
        synchronize-fn
        (or (:synchronize-fn options) product-repository/synchronize!)
        synchronization
        (synchronize-fn
         (cond-> {:workspace-root (:workspace-root execution)
                  :target-contract (:contract execution)}
           (:run-command! options)
           (assoc :run-command! (:run-command! options))))
        _ (when (and exact-commit? (seq (:changes synchronization)))
            (fail! "Exact generated-product commit did not synchronize cleanly"
                   {:target (:target execution)
                    :reason :post-commit-managed-changes
                    :changes (:changes synchronization)}))]
    {:target (:target execution)
     :mode (if exact-commit?
             :exact-commit-proof
             :pre-commit-synchronization)
     :proof proof
     :precommit-proof precommit-proof
     :repository-proof repository-proof
     :test-suites test-suite-verification
     :consumer-tests test-suite-verification
     :staging-cleanup staging-cleanup
     :synchronization synchronization}))

(defn prepare-publication!
  "Runs complete target proof and prepares a local product branch/commit, pull
  request metadata, and staged parent gitlink. No push or PR is performed."
  [options]
  (let [execution (plan (assoc options :profile nil))
        _ (generated-publication! execution)
        proof (publication-proof! execution options)
        test-suite-verification
        ((or (:test-suites-fn options)
             (:consumer-tests-fn options)
             consumer-tests/verify!)
         {:workspace-root (:workspace-root execution)
          :target-contract (:contract execution)
          :run-command! (:run-command! options)})
        staging-cleanup
        ((or (:staging-cleanup-fn options)
             product-staging/clean-build-artifacts!)
         {:workspace-root (:workspace-root execution)
          :target-contract (:contract execution)})
        prepare-fn (or (:prepare-fn options) product-repository/prepare!)
        preparation
        (prepare-fn
         (cond-> {:workspace-root (:workspace-root execution)
                  :target-contract (:contract execution)
                  :branch (:branch options)
                  :commit-message (:commit-message options)
                  :pull-request-title (:pull-request-title options)
                  :pull-request-body (:pull-request-body options)}
           (:run-command! options)
           (assoc :run-command! (:run-command! options))))]
    {:target (:target execution)
     :proof proof
     :test-suites test-suite-verification
     :consumer-tests test-suite-verification
     :staging-cleanup staging-cleanup
     :preparation preparation}))

(defn prepare-alpha-release!
  "Runs the complete target proof, then assembles local deterministic alpha ZIPs
  from the exact clean product commit that matches proved staging. No tag,
  release, upload, or push is performed."
  [options]
  (let [execution (plan (assoc options :profile nil))
        _ (generated-publication! execution)
        inventory
        (or (:inventory options)
            (alpha-release/read-inventory! (:contract execution)))
        _ (alpha-release/validate-inventory!
           (:contract execution) inventory)
        _ (alpha-release/validate-request!
           (:authorized-tag options) (:product-commit options))
        _ (alpha-release/select-platforms!
           inventory (:platform-ids options))
        proof (publication-proof! execution options)
        release-fn (or (:release-fn options) alpha-release/prepare!)
        preparation
        (release-fn
         (cond->
          {:workspace-root (:workspace-root execution)
           :target-contract (:contract execution)
           :inventory inventory
           :authorized-tag (:authorized-tag options)
           :product-commit (:product-commit options)}
           (:platform-ids options)
           (assoc :platform-ids (:platform-ids options))
           (:output-root options)
           (assoc :output-root (:output-root options))
           (:run-command! options)
           (assoc :run-command! (:run-command! options))
           (:build-fn options)
           (assoc :build-fn (:build-fn options))
           (:framework-assemblies options)
           (assoc :framework-assemblies
                  (:framework-assemblies options))))]
    {:target (:target execution)
     :proof proof
     :preparation preparation}))

(defn run!
  [command options]
  (case command
    :generate (generate! options)
    :verify (verify! options)
    :pack (pack! options)
    :package (package! options)
    :differential (differential! options)
    :proof (proof! options)
    :synchronize (synchronize! options)
    :prepare-publication (prepare-publication! options)
    :prepare-alpha-release (prepare-alpha-release! options)
    (fail! "Unknown target execution command" {:command command})))

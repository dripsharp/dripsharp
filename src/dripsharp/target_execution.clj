(ns dripsharp.target-execution
  "Metadata-driven execution of one preflighted target-directory contract."
  (:refer-clojure :exclude [run!])
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.compiler :as compiler]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.java-project :as java-project]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
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
                 (baseline/hydrate-profile
                  root (:configuration record))]))
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
          :differential-fn))

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
      (generate-fn
       (merge
        (stage-options options)
        {:workspace-root (:workspace-root execution)
         :profile profile
         :read-profile-fn (:read-profile execution)
         :read-destination-fn (:read-destination execution)}
        {:emit-project-fn emit-project-fn})))))

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

(defn proof!
  "Runs every required proof ladder declared by one preflighted target.
  Ladders are mandatory, exhaustive over target profiles and validations, and
  carry their CI resource class in the target contract."
  [options]
  (let [execution (plan (assoc options :profile nil))
        validations (get-in execution [:contract :validation-contracts])
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

(defn run!
  [command options]
  (case command
    :generate (generate! options)
    :verify (verify! options)
    :pack (pack! options)
    :package (package! options)
    :differential (differential! options)
    :proof (proof! options)
    (fail! "Unknown target execution command" {:command command})))

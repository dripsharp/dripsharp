(ns dripsharp.sqltrellis.registration
  "Executable registration evidence for the pinned SqlTrellis target."
  (:require [dripsharp.baseline :as baseline]
            [dripsharp.harness :as harness]
            [dripsharp.maven :as maven]
            [dripsharp.paths :as paths]
            [dripsharp.sqltrellis.java-project :as sqltrellis]
            [dripsharp.target-directory :as target-directory]))

(defn verify!
  "Runs pinned Maven discovery and validates the retained neutral graph."
  ([] (verify! {}))
  ([{:keys [workspace-root]
     :or {workspace-root (paths/workspace-root)}}]
   (let [root (paths/absolute workspace-root)
         target (target-directory/read-target root :sqltrellis)
         profile-file "targets/sqltrellis/profiles/core.edn"
         profile (harness/read-profile root profile-file)
         input (harness/discover-project!
                {:workspace-root root
                 :build-tool :maven
                 :project-root (:project-root profile)
                 :maven-project-id (:maven-project-id profile)
                 :selected-projects (:maven-selected-projects profile)
                 :properties (:maven-properties profile)
                 :build-input-contract (:maven-build-input-contract profile)
                 :lifecycle-phase (name (:maven-lifecycle-phase profile))})]
     (sqltrellis/validate-project-input!
      {:workspace-root root :profile profile :project-input input})
     {:target (:target target)
      :revision (get-in (baseline/read-baseline root :sqltrellis)
                        [:upstream :revision])
      :project-id (:project-id input)
      :ordinary-sources (count (:production-sources input))
      :generated-sources (count (:generated-production-sources input))
      :test-sources (count (:test-sources input))
      :test-resources (count (:test-resources input))
      :generation-executions (:generation-executions input)
      :generation-artifacts (count (:build-input-artifacts input))
      :generation-artifacts-sha256
      (maven/generation-artifacts-sha256 (:build-input-artifacts input))})))

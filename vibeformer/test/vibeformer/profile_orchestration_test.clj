(ns vibeformer.profile-orchestration-test
  (:require [clojure.test :refer [deftest is testing]]
            [vibeformer.compiler :as compiler]
            [vibeformer.harness :as harness]
            [vibeformer.java-project :as java-project]
            [vibeformer.packaging :as packaging]
            [vibeformer.paths :as paths]
            [vibeformer.public-surface :as public-surface])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]))

(defn- temp-directory []
  (Files/createTempDirectory "vibeformer-profile-orchestration"
                             (make-array FileAttribute 0)))

(defn- write-file! [^Path root relative content]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- encoded [value]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes ^String value StandardCharsets/UTF_8)))

(defn- directory-bytes [^Path root]
  (with-open [files (Files/walk root (make-array FileVisitOption 0))]
    (->> (.toArray files)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (map (fn [^Path file]
                [(str (.relativize root file)) (vec (Files/readAllBytes file))]))
         (into (sorted-map)))))

(defn- fixture! []
  (let [workspace (paths/workspace-root)
        fixture (temp-directory)
        _ (write-file! fixture "settings.gradle"
                       "rootProject.name = 'profile-fixture'\n")
        _ (write-file! fixture "build.gradle"
                       (str "plugins { id 'java-library' }\n"
                            "java { sourceCompatibility = JavaVersion.VERSION_17 }\n"))
        _ (write-file! fixture "src/main/java/example/Greeting.java"
                       "package example; public final class Greeting {}\n")
        surface (write-file!
                 fixture "PublicSurface.tsv"
                 (str "VIBEFORMER_JAVA_LIBRARY_PUBLIC_SURFACE_V1\n"
                      "surface\t"
                      (encoded (str "constructor|example.Greeting|public|"
                                    "type-parameters=|parameters=|throws=")) "\n"
                      "surface\t"
                      (encoded (str "type|class|public final|example.Greeting|"
                                    "type-parameters=|extends=java.lang.Object|implements="))
                      "\n"))
        compiled-surface
        (write-file!
         fixture "CompiledPublicSurface.tsv"
         (str "# VIBEFORMER_DOTNET_ACCESSIBLE_CONTRACT_V1\n"
              "assembly\towner\tkind\tname\tparameter-count\tvisibility\t"
              "metadata-flags\tsignature\tgeneric-constraints\tnullability\t"
              "source-provenance\t"
              "source-declaration\ttranslation-rule\n"
              "Example.Profile.Library\tExample.Profile.Library.Greeting\tconstructor\t"
              ".ctor\t0\tpublic\t0x1886\tGreeting .ctor()\t-\t-\t"
              (str (.toRealPath
                    (paths/resolve-path fixture "src/main/java/example/Greeting.java")
                    (make-array java.nio.file.LinkOption 0)))
              ":1\tconstructor|example.Greeting|public|type-parameters=|parameters=|"
              "throws=\tjava-implicit-constructor\n"
              "Example.Profile.Library\tExample.Profile.Library.Greeting\ttype\tGreeting\t"
              "0\tpublic\t0x100101\tclass Greeting\t-\ttype=oblivious\t"
              (str (.toRealPath
                    (paths/resolve-path fixture "src/main/java/example/Greeting.java")
                    (make-array java.nio.file.LinkOption 0)))
              ":1\ttype|class|public final|example.Greeting|type-parameters=|"
              "extends=java.lang.Object|implements=\tjava-declaration\n"))
        destination
        {:schema-version 1
         :product-family :java-library
         :destination-bundle 'vibeformer.java-library/rule-bundle
         :project {:assembly-name "Example.Profile.Library"
                   :root-namespace "Example.Profile.Library"
                   :target-framework "net8.0"
                   :nullable "enable"
                   :implicit-usings false
                   :warnings-as-errors true}
         :package {:id "Example.Profile.Library"
                   :version "1.0.0"
                   :title "Example profile library"
                   :description "Independent product-neutral profile fixture."
                   :authors "Vibeformer"
                   :tags "java profile fixture"
                   :project-url "https://example.invalid/profile-library"
                   :repository-url "https://example.invalid/profile-library.git"
                   :repository-type "git"}
         :output {:project-directory "generated/example-profile-library"
                  :source-directory "src"
                  :resource-directory "resources"
                  :project-file "Example.Profile.Library.csproj"
                  :source-map-file "source-map.edn"
                  :diagnostics-file "diagnostics.edn"
                  :manifest-file "generation-manifest.edn"
                  :public-metadata-file "public-metadata.edn"
                  :annotation-decisions-file "annotation-decisions.edn"}
         :namespaces {"example" "Example.Profile.Library"}
         :resources {}
         :resource-policy {:strategy :embedded-resource-preserve-path}
         :project-dependencies []
         :external-dependencies {}
         :public-surface
         {:strategy 'vibeformer.java-library/public-surface-strategy
          :contract-file (str surface)
          :compiled-contract-file (str compiled-surface)}
         :package-consumer
         {:strategy :compile-only
          :project-file "Example.Profile.Library.PackageConsumer.csproj"
          :compile-types ["Example.Profile.Library.Greeting"]
          :success-message "Independent Example.Profile.Library package restore passed."}}
        destination-file (write-file! fixture "destination.edn"
                                      (str (pr-str destination) "\n"))
        profile
        {:schema-version 1
         :profile "example-profile-library"
         :product-family :java-library
         :project-root (str fixture)
         :gradle-wrapper (str (paths/resolve-path workspace "research" "pkl" "gradlew"))
         :gradle-project ":"
         :destination-bundle 'vibeformer.java-library/rule-bundle
         :destination-config (str destination-file)
         :dependency-profiles []
         :identity-guard {:forbidden-fragments ["reserved-product-identity"]}}
        profile-file (write-file! fixture "profile.edn" (str (pr-str profile) "\n"))]
    {:workspace workspace :profile-file profile-file}))

(deftest complete-independent-profile-selects-an-explicit-pinned-public-contract
  (let [workspace (paths/workspace-root)
        profile (harness/read-profile workspace
                                      "vibeformer/config/rawhttp-core.edn")
        destination (java-project/read-configuration
                     workspace (:destination-config profile))
        strategy (public-surface/resolve-strategy!
                  (:product-family profile) (:public-surface destination))
        surface (public-surface/read! strategy workspace)]
    (is (= :java-library (:product-family profile)
           (:product-family destination)
           (get-in strategy [:contract :product-family])))
    (is (= 'vibeformer.java-library/rule-bundle
           (:destination-bundle profile)
           (:destination-bundle destination)))
    (is (= 510 (count (:rows surface))))
    (is (= "vibeformer/validation/rawhttp-core/CompiledPublicSurface.tsv"
           (get-in destination [:public-surface :compiled-contract-file])))
    (is (empty? (:seeds surface))
        "the complete independent project is audited without a source allowlist")
    (is (= true (get-in destination [:project :warnings-as-errors])))
    (is (= "enable" (get-in destination [:project :nullable])))
    (is (= "947cfdc619100a23f5e429ccb3c42ba6fedc8141"
           (get-in destination [:package :repository-commit])))
    (is (= :compile-only
           (get-in destination [:external-dependencies
                                "com.google.code.findbugs:jsr305:3.0.2"
                                :source-scope])))
    (is (= {:strategy :source-file
            :project-file "RawHttp.Core.PackageConsumer.csproj"
            :fixture-file "RawHttp.Core.Program.cs"
            :success-message "Independent RawHttp.Core package behavior passed."}
           (:package-consumer destination)))))

(deftest arbitrary-non-product-profile-runs-every-normal-package-stage-deterministically
  (let [{:keys [workspace profile-file]} (fixture!)
        profile (str profile-file)
        first-generation (harness/generate! {:workspace-root workspace
                                             :profile profile
                                             :worker-count 1})
        first-bytes (directory-bytes (get-in first-generation
                                             [:emission :project-root]))
        second-generation (harness/generate! {:workspace-root workspace
                                              :profile profile
                                              :worker-count 3})
        second-bytes (directory-bytes (get-in second-generation
                                              [:emission :project-root]))]
    (testing "generate and verify use the explicit non-product contracts"
      (is (= first-bytes second-bytes))
      (is (= :java-library (get-in second-generation [:emission :rule-bundle])))
      (is (= 2 (get-in second-generation
                       [:emission :public-metadata :required-rows])))
      (let [verification (compiler/verify-clean-build!
                          {:workspace-root workspace :profile profile})]
        (is (empty? (:diagnostics verification)))
        (is (= 2 (get-in verification
                         [:public-surface :assemblies 0 :contract-members])))
        (is (= {:types 1 :members 1 :rows 2}
               (select-keys (get-in verification
                                    [:public-surface :assemblies 0])
                            [:types :members :rows])))))
    (testing "pack repeats clean builds and publishes one byte-stable package"
      (let [proof (packaging/pack-verified-profile!
                   {:workspace-root workspace :profile profile})]
        (is (= 2 (get-in proof [:summary :clean-builds])))
        (is (= "Example.Profile.Library" (get-in proof [:identity :id])))))
    (testing "package restores into a generated package-reference-only consumer"
      (let [proof (packaging/verify-package-consumption!
                   {:workspace-root workspace :profile profile})]
        (is (= ["Example.Profile.Library" "1.0.0"]
               (get-in proof [:dependency-proof :package-reference])))
        (is (re-find #"Independent Example[.]Profile[.]Library package restore passed[.]"
                     (get-in proof [:run :output])))))))

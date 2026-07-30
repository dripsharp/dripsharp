(ns dripsharp.alpha-release-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.alpha-release :as alpha-release]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.target-execution :as target-execution]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipEntry ZipOutputStream]))

(defn- temp-directory
  []
  (Files/createTempDirectory "dripsharp-alpha-release-test-"
                             (make-array FileAttribute 0)))

(defn- delete-tree!
  [^Path root]
  (when (Files/exists root (make-array java.nio.file.LinkOption 0))
    (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
      (doseq [^Path entry
              (->> (.toArray entries)
                   (map #(cast Path %))
                   (sort-by #(.getNameCount ^Path %) >))]
        (Files/delete entry)))))

(defn- write!
  [^Path root relative content]
  (let [file (.resolve root relative)]
    (Files/createDirectories (.getParent file)
                             (make-array FileAttribute 0))
    (Files/writeString file content StandardCharsets/UTF_8
                       (make-array OpenOption 0))
    file))

(defn- git!
  [directory & command]
  (process/run! {:command (into ["git"] command)
                 :directory directory}))

(defn- git-output
  [directory & command]
  (str/trim (:output (apply git! directory command))))

(defn- configure-git!
  [directory]
  (git! directory "config" "user.name" "DripSharp Release Test")
  (git! directory "config" "user.email"
        "dripsharp-release@example.invalid"))

(defn- actual-contract-and-inventory
  [target]
  (let [contract (target-directory/read-target target)]
    [contract (alpha-release/read-inventory! contract)]))

(defn- synthetic-contract
  [workspace inventory]
  (let [family (:product-family inventory)
        product-id (name family)
        profile-records
        (into
         {}
         (map-indexed
          (fn [index {:keys [file project]}]
            (let [assembly (subs file 0 (- (count file) 4))
                  profile (str "release-profile-" index)]
              [profile
               {:descriptor {:id profile}
                :destination
                {:configuration
                 {:project
                  {:assembly-name assembly
                   :target-framework (:target-framework inventory)}}}
                :release-project project}]))
          (:product-assemblies inventory)))
        profile-projects
        (into {}
              (map (fn [[profile record]]
                     [profile (:release-project record)]))
              profile-records)]
    {:target (keyword (str "release-" product-id))
     :product-family family
     :workspace-root workspace
     :target-directory
     (paths/resolve-path workspace "targets"
                         (str "release-" product-id))
     :profiles profile-records
     :publication
     {:kind :generated-repository
      :repository-slug (str "dripsharp/" product-id)
      :repository-url
      (str "https://github.com/dripsharp/" product-id ".git")
      :default-branch "master"
      :submodule-path (str "products/" product-id)
      :staging-path (str "target/generated/" product-id)
      :profile-projects profile-projects
      :managed-paths ["src" "tests" "LICENSE" "NOTICE" "README.md"]
      :consumer-tests {:schema-version 1}
      :publication-mode :pull-request}}))

(defn- generated-files
  [inventory]
  (into
   [["LICENSE" "Apache License\n"]
    ["NOTICE" "Generated product notice\n"]
    ["README.md" "# Generated product\n"]
    ["tests/GeneratedConsumerTests.cs"
     "namespace Generated.Consumer.Tests;\n"]]
   (mapcat
    (fn [{:keys [file project]}]
      (let [assembly (subs file 0 (- (count file) 4))]
        [[(str project "/" assembly ".csproj")
          (str "<Project><PropertyGroup><TargetFramework>"
               (:target-framework inventory)
               "</TargetFramework></PropertyGroup></Project>\n")]
         [(str project "/Generated.cs")
          (str "namespace " assembly ";\n")]]))
    (:product-assemblies inventory))))

(defn- release-fixture!
  [inventory]
  (let [workspace (temp-directory)
        contract (synthetic-contract workspace inventory)
        publication (:publication contract)
        product (paths/resolve-path workspace
                                    (:submodule-path publication))
        staging (paths/resolve-path workspace
                                    (:staging-path publication))
        files (generated-files inventory)
        _ (git! workspace "init" "-b" "master")
        _ (configure-git! workspace)
        _ (write! workspace ".gitignore" "target/\n")
        _ (Files/createDirectories product (make-array FileAttribute 0))
        _ (git! product "init" "-b" "master")
        _ (configure-git! product)
        _ (git! product "remote" "add" "origin"
                (get-in contract [:publication :repository-url]))
        _ (doseq [[path content] files]
            (write! product path content)
            (write! staging path content))
        _ (git! product "add" "--all")
        _ (git! product "commit" "-m" "Generated product state")
        commit (git-output product "rev-parse" "HEAD")
        submodule-path (:submodule-path publication)
        _ (write! workspace ".gitmodules"
                  (str "[submodule \"" submodule-path "\"]\n"
                       "\tpath = " submodule-path "\n"
                       "\turl = " (:repository-url publication) "\n"))
        _ (git! workspace "add" ".gitignore" ".gitmodules")
        _ (git! workspace "update-index" "--add" "--cacheinfo"
                (str "160000," commit "," submodule-path))
        _ (git! workspace "commit" "-m" "Pin generated product")]
    {:workspace workspace
     :contract contract
     :inventory inventory
     :product product
     :staging staging
     :commit commit}))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- fake-build!
  [calls]
  (fn [{:keys [inventory platform configuration build-output]}]
    (swap! calls conj
           {:platform (:id platform)
            :runtime-identifier (:runtime-identifier platform)
            :configuration configuration})
    (doseq [file
            (concat (map :file (:product-assemblies inventory))
                    (map :file (:managed-dependencies inventory))
                    (map :file (:native-assets platform)))]
      (write! build-output file
              (str (:product-family inventory) "\t"
                   (:id platform) "\t" file "\n")))
    ;; These are normal build byproducts, never selected release inputs.
    (write! build-output "ignored.pdb" "symbols\n")
    (write! build-output "ignored.xml" "documentation\n")
    (write! build-output "ignored.nupkg" "package\n")
    {:configuration configuration}))

(defn- zip!
  [^Path output entries]
  (Files/createDirectories (.getParent output)
                           (make-array FileAttribute 0))
  (with-open [stream
              (ZipOutputStream.
               (Files/newOutputStream output (make-array OpenOption 0)))]
    (doseq [[name content] entries]
      (.putNextEntry stream (ZipEntry. name))
      (.write stream (.getBytes (str content) StandardCharsets/UTF_8))
      (.closeEntry stream)))
  output)

(defn- hashes
  [entries]
  (into {}
        (map (fn [[name content]]
               [name
                (util/sha256-bytes
                 (.getBytes (str content) StandardCharsets/UTF_8))]))
        entries))

(defn- expected-entry-map
  [inventory platform]
  (into
   (sorted-map)
   (map
    (fn [file] [file (str "binary:" file)])
    (concat (map :file (:product-assemblies inventory))
            (map :file (:managed-dependencies inventory))
            (map :file (:native-assets platform))))))

(deftest target-owned-release-inventories-cover-both-product-families
  (let [[brine-contract brine] (actual-contract-and-inventory :pkl)
        [pdfcarton-contract pdfcarton]
        (actual-contract-and-inventory :pdfcube)]
    (is (= brine
           (alpha-release/validate-inventory! brine-contract brine)))
    (is (= pdfcarton
           (alpha-release/validate-inventory!
            pdfcarton-contract pdfcarton)))
    (is (= #{"DripSharp.Brine.dll"
             "DripSharp.Brine.Parser.dll"}
           (set (map :file (:product-assemblies brine)))))
    (is (empty? (:managed-dependencies brine)))
    (is (= [{:id "portable"
             :runtime-identifier nil
             :native-assets []}]
           (:platforms brine)))
    (is (= #{"DripSharp.PdfCarton.dll"
             "DripSharp.PdfCarton.IO.dll"
             "DripSharp.PdfCarton.Fonts.dll"
             "DripSharp.PdfCarton.Xmp.dll"
             "DripSharp.PdfCarton.Preflight.dll"}
           (set (map :file (:product-assemblies pdfcarton)))))
    (is (= #{"Microsoft.Extensions.DependencyInjection.Abstractions.dll"
             "Microsoft.Extensions.Logging.Abstractions.dll"
             "SkiaSharp.dll"
             "System.Security.Cryptography.Pkcs.dll"}
           (set (map :file (:managed-dependencies pdfcarton)))))
    (is (= #{"win-x64" "win-arm64" "linux-x64" "linux-arm64"
             "osx-x64" "osx-arm64"}
           (set (map :id (:platforms pdfcarton)))))
    (is (= ["osx-x64" "osx-arm64"]
           (mapv :id
                 (alpha-release/select-platforms!
                  pdfcarton ["osx-arm64" "osx-x64"]))))
    (doseq [selection [[] ["osx-x64" "osx-x64"] [""] "osx-x64"]]
      (is (= :invalid-release-platform-selection
             (:reason
              (failure
               #(alpha-release/select-platforms!
                 pdfcarton selection))))))
    (let [result
          (failure
           #(alpha-release/select-platforms!
             pdfcarton ["osx-x64" "plan9-x64"]))]
      (is (= :invalid-release-platform-selection (:reason result)))
      (is (= ["plan9-x64"] (:unknown result))))
    (is (every? #(= 1 (count (:native-assets %)))
                (:platforms pdfcarton)))
    (is (= #{"SkiaSharp.NativeAssets.Win32"
             "SkiaSharp.NativeAssets.Linux"
             "SkiaSharp.NativeAssets.macOS"}
           (set
            (mapcat
             #(map :package-id (:native-assets %))
             (:platforms pdfcarton)))))))

(deftest release-inventory-rejects-symbolic-link-substitution
  (let [[_ inventory] (actual-contract-and-inventory :pkl)
        workspace (temp-directory)
        contract (synthetic-contract workspace inventory)
        target-root (:target-directory contract)
        substitute
        (write! target-root "substitute-release.edn"
                (str (pr-str inventory) "\n"))
        release-file (paths/resolve-path target-root "release.edn")]
    (try
      (Files/createSymbolicLink release-file (.getFileName substitute)
                                (make-array FileAttribute 0))
      (let [result
            (failure #(alpha-release/read-inventory! contract))]
        (is (= :symbolic-link-release-inventory (:reason result)))
        (is (= (str release-file) (:path result))))
      (finally
        (delete-tree! workspace)))))

(deftest assembly-includes-managed-and-native-assets-and-repeats-deterministically
  (let [[_ inventory] (actual-contract-and-inventory :pdfcube)
        {:keys [workspace contract commit]}
        (release-fixture! inventory)
        calls (atom [])
        commands (atom [])
        run-command!
        (fn [request]
          (swap! commands conj (:command request))
          (process/run! request))]
    (try
      (let [options
            {:workspace-root workspace
             :target-contract contract
             :inventory inventory
             :authorized-tag "v0.1.0-alpha.1"
             :product-commit commit
             :run-command! run-command!
             :build-fn (fake-build! calls)
             :framework-assemblies #{"System.Runtime.dll"}}
            first
            (alpha-release/prepare!
             (assoc options :output-root
                    (paths/resolve-path workspace "release-a")))
            second
            (alpha-release/prepare!
             (assoc options :output-root
                    (paths/resolve-path workspace "release-b")))
            selected
            (alpha-release/prepare!
             (assoc options
                    :platform-ids ["osx-arm64" "osx-x64"]
                    :output-root
                    (paths/resolve-path workspace "release-c")))
            first-sha
            (into {} (map (juxt :filename :sha256)) (:assets first))
            second-sha
            (into {} (map (juxt :filename :sha256)) (:assets second))]
        (is (= first-sha second-sha))
        (is (= 6 (count first-sha)))
        (is (= ["osx-x64" "osx-arm64"] (:platforms selected)))
        (is (= #{"DripSharp.PdfCarton-0.1.0-alpha.1-net10.0-osx-x64.zip"
                 "DripSharp.PdfCarton-0.1.0-alpha.1-net10.0-osx-arm64.zip"}
               (set (map :filename (:assets selected)))))
        (is (= (set (map :filename (:assets selected)))
               (set
                (map :filename
                     (get-in selected [:github-release :assets])))))
        (is (str/includes?
             (get-in selected [:github-release :notes]) "`osx-x64`"))
        (is (not
             (str/includes?
              (get-in selected [:github-release :notes]) "`win-x64`")))
        (is (contains?
             first-sha
             "DripSharp.PdfCarton-0.1.0-alpha.1-net10.0-osx-arm64.zip"))
        (let [common
              #{"DripSharp.PdfCarton.dll"
                "DripSharp.PdfCarton.IO.dll"
                "DripSharp.PdfCarton.Fonts.dll"
                "DripSharp.PdfCarton.Xmp.dll"
                "DripSharp.PdfCarton.Preflight.dll"
                "Microsoft.Extensions.DependencyInjection.Abstractions.dll"
                "Microsoft.Extensions.Logging.Abstractions.dll"
                "SkiaSharp.dll"
                "System.Security.Cryptography.Pkcs.dll"}]
          (doseq [asset (:assets first)
                  :let [entries (set (keys (:entries asset)))]]
            (is (set/subset? common entries))
            (is (= 1 (count (set/difference entries common))))
            (is (= 10 (count entries)))))
        (is (every? #(= "Release" (:configuration %)) @calls))
        (is (= #{"win-x64" "win-arm64" "linux-x64" "linux-arm64"
                 "osx-x64" "osx-arm64"}
               (set (map :runtime-identifier @calls))))
        (is (not-any?
             (fn [command]
               (or (= "gh" (first command))
                   (some #{"push" "tag" "release" "upload"} command)))
             @commands))
        (is (= {:repository "dripsharp/pdfcarton"
                :authorized-tag "v0.1.0-alpha.1"
                :target-commitish commit
                :prerelease true
                :latest false
                :notes
                (str
                 "# DripSharp.PdfCarton 0.1.0-alpha.1\n\n"
                 "This is an alpha DLL prerelease for net10.0.\n\n"
                 "- Product commit: `" commit "`\n"
                 "- NuGet publication: none; this release contains DLL ZIP assets only.\n"
                 "- Prepared platforms:\n"
                 "  - `win-x64` (runtime identifier `win-x64`): "
                 "`DripSharp.PdfCarton-0.1.0-alpha.1-net10.0-win-x64.zip`\n"
                 "  - `win-arm64` (runtime identifier `win-arm64`): "
                 "`DripSharp.PdfCarton-0.1.0-alpha.1-net10.0-win-arm64.zip`\n"
                 "  - `linux-x64` (runtime identifier `linux-x64`): "
                 "`DripSharp.PdfCarton-0.1.0-alpha.1-net10.0-linux-x64.zip`\n"
                 "  - `linux-arm64` (runtime identifier `linux-arm64`): "
                 "`DripSharp.PdfCarton-0.1.0-alpha.1-net10.0-linux-arm64.zip`\n"
                 "  - `osx-x64` (runtime identifier `osx-x64`): "
                 "`DripSharp.PdfCarton-0.1.0-alpha.1-net10.0-osx-x64.zip`\n"
                 "  - `osx-arm64` (runtime identifier `osx-arm64`): "
                 "`DripSharp.PdfCarton-0.1.0-alpha.1-net10.0-osx-arm64.zip`\n")
                :assets
                (mapv #(select-keys % [:filename :sha256])
                      (:assets first))}
               (:github-release first)))
        (is (= [:tag-or-release-creation-requires-authorization
                :asset-upload-requires-authorization
                :push-requires-authorization]
               (:external-actions first))))
      (finally
        (delete-tree! workspace)))))

(deftest portable-brine-assembly-produces-one-versioned-framework-asset
  (let [[_ inventory] (actual-contract-and-inventory :pkl)
        {:keys [workspace contract commit]}
        (release-fixture! inventory)]
    (try
      (let [prepared
            (alpha-release/prepare!
             {:workspace-root workspace
              :target-contract contract
              :inventory inventory
              :authorized-tag "0.1.0-alpha.2"
              :product-commit commit
              :output-root (paths/resolve-path workspace "release")
              :build-fn (fake-build! (atom []))
              :framework-assemblies #{"System.Runtime.dll"}})
            _ (is (= 1 (count (:assets prepared))))
            asset (first (:assets prepared))]
        (is (= "DripSharp.Brine-0.1.0-alpha.2-net10.0-portable.zip"
               (:filename asset)))
        (is (= #{"DripSharp.Brine.dll"
                 "DripSharp.Brine.Parser.dll"}
               (set (keys (:entries asset)))))
        (is (nil? (:runtime-identifier asset)))
        (is (=
             (str
              "# DripSharp.Brine 0.1.0-alpha.2\n\n"
              "This is an alpha DLL prerelease for net10.0.\n\n"
              "- Product commit: `" commit "`\n"
              "- NuGet publication: none; this release contains DLL ZIP assets only.\n"
              "- Prepared platforms:\n"
              "  - `portable` (portable; no runtime identifier): "
              "`DripSharp.Brine-0.1.0-alpha.2-net10.0-portable.zip`\n")
             (get-in prepared [:github-release :notes]))))
      (finally
        (delete-tree! workspace)))))

(deftest release-verification-rejects-forbidden-missing-unexpected-and-framework-files
  (let [[contract inventory] (actual-contract-and-inventory :pdfcube)
        platform
        (first (filter #(= "linux-x64" (:id %))
                       (:platforms inventory)))
        root (temp-directory)
        expected (expected-entry-map inventory platform)]
    (try
      (testing "the exact managed and native inventory passes"
        (let [asset (zip! (.resolve root "valid.zip") expected)]
          (is (= (set (keys expected))
                 (set
                  (keys
                   (:entries
                    (alpha-release/verify-asset!
                     {:artifact asset
                      :inventory inventory
                      :platform platform
                      :expected-hashes (hashes expected)
                      :framework-assemblies #{"System.Runtime.dll"}}))))))))
      (testing "forbidden release payloads fail before unrelated-file handling"
        (doseq [file ["leaked.nupkg" "leaked.pdb" "leaked.xml"
                      "source.zip" "source.tar.gz"]]
          (let [entries (assoc expected file "forbidden")
                asset (zip! (.resolve root (str file ".asset")) entries)]
            (is (= :forbidden-release-file
                   (:reason
                    (failure
                     #(alpha-release/verify-asset!
                       {:artifact asset
                        :inventory inventory
                        :platform platform
                        :expected-hashes (hashes entries)
                        :framework-assemblies
                        #{"System.Runtime.dll"}}))))))))
      (testing "missing managed and native dependencies are reported separately"
        (let [entries
              (dissoc expected
                      "SkiaSharp.dll"
                      "libSkiaSharp.so")
              asset (zip! (.resolve root "missing.asset") entries)
              result
              (failure
               #(alpha-release/verify-asset!
                 {:artifact asset
                  :inventory inventory
                  :platform platform
                  :expected-hashes (hashes entries)
                  :framework-assemblies #{"System.Runtime.dll"}}))]
          (is (= :release-asset-mismatch (:reason result)))
          (is (= ["SkiaSharp.dll"] (:missing-managed result)))
          (is (= ["libSkiaSharp.so"] (:missing-native result)))))
      (testing "unexpected assemblies, native libraries, and unrelated files fail"
        (let [entries
              (assoc expected
                     "Shadow.dll" "managed"
                     "libShadow.so" "native"
                     "README.md" "unrelated")
              asset (zip! (.resolve root "unexpected.asset") entries)
              result
              (failure
               #(alpha-release/verify-asset!
                 {:artifact asset
                  :inventory inventory
                  :platform platform
                  :expected-hashes (hashes entries)
                  :framework-assemblies #{"System.Runtime.dll"}}))]
          (is (= :release-asset-mismatch (:reason result)))
          (is (= ["Shadow.dll"] (:unexpected-managed result)))
          (is (= ["libShadow.so"] (:unexpected-native result)))
          (is (= ["README.md"] (:unrelated result)))))
      (testing "framework assemblies fail even when they are otherwise unexpected"
        (let [entries (assoc expected "System.Runtime.dll" "framework")
              asset (zip! (.resolve root "framework.asset") entries)]
          (is (= :framework-assembly
                 (:reason
                  (failure
                   #(alpha-release/verify-asset!
                     {:artifact asset
                      :inventory inventory
                      :platform platform
                      :expected-hashes (hashes entries)
                      :framework-assemblies
                      #{"System.Runtime.dll"}})))))))
      (testing "typed inventory rejects dependency-name collisions"
        (let [colliding
              (update
               inventory :managed-dependencies conj
               {:file "libSkiaSharp.dll"
                :package-id "libSkiaSharp"
                :version "1.0.0"})]
          (is (= :dependency-name-collision
                 (:reason
                  (failure
                   #(alpha-release/validate-inventory!
                     contract colliding)))))))
      (testing "typed inventory rejects case-insensitive dependency-name collisions"
        (let [colliding
              (update
               inventory :managed-dependencies conj
               {:file "dripsharp.pdfcarton.dll"
                :package-id "dripsharp.pdfcarton"
                :version "1.0.0"})
              result
              (failure
               #(alpha-release/validate-inventory!
                 contract colliding))]
          (is (= :dependency-name-collision (:reason result)))
          (is (= #{"DripSharp.PdfCarton.dll"
                   "dripsharp.pdfcarton.dll"}
                 (set
                  (map :file
                       (get-in
                        result
                        [:collisions "dripsharp.pdfcarton.dll"])))))))
      (testing "typed inventory rejects Windows-illegal filenames"
        (doseq [file ["CON.dll" "nul.DLL" "COM1.dll" "lpt9.dll"]]
          (let [package-id (subs file 0 (- (count file) 4))
                malformed
                (update
                 inventory :managed-dependencies conj
                 {:file file
                  :package-id package-id
                  :version "1.0.0"})
                result
                (failure
                 #(alpha-release/validate-inventory!
                   contract malformed))]
            (is (= :invalid-release-inventory (:reason result)))
            (is (= file (:file result))))))
      (testing "typed inventory rejects Windows-illegal relative path components"
        (doseq [path ["runtimes/win:x64/native/libSkiaSharp.dll"
                      "runtimes/CON/native/libSkiaSharp.dll"
                      "runtimes/win-x64./native/libSkiaSharp.dll"
                      "runtimes/win-x64/native*/libSkiaSharp.dll"
                      "runtimes/win-x64\u0085/native/libSkiaSharp.dll"]]
          (let [malformed
                (assoc-in inventory
                          [:platforms 0 :native-assets 0 :package-path]
                          path)
                result
                (failure
                 #(alpha-release/validate-inventory!
                   contract malformed))]
            (is (= :invalid-release-inventory (:reason result)))
            (is (= path (:path result))))))
      (testing "typed inventory rejects Windows-illegal product project paths"
        (let [path "src/CON"
              malformed
              (assoc-in inventory
                        [:product-assemblies 0 :project]
                        path)
              result
              (failure
               #(alpha-release/validate-inventory!
                 contract malformed))]
          (is (= :invalid-release-inventory (:reason result)))
          (is (= path (:path result)))))
      (testing "downloaded ZIPs reject case-insensitive entry collisions"
        (let [entries
              (assoc expected
                     "dripsharp.pdfcarton.dll"
                     "case-only collision")
              asset (zip! (.resolve root "case-collision.asset") entries)
              result
              (failure
               #(alpha-release/verify-asset!
                 {:artifact asset
                  :inventory inventory
                  :platform platform
                  :expected-hashes (hashes entries)
                  :framework-assemblies #{"System.Runtime.dll"}}))]
          (is (= :dependency-name-collision (:reason result)))
          (is (= ["DripSharp.PdfCarton.dll"
                  "dripsharp.pdfcarton.dll"]
                 (get-in
                  result
                  [:collisions "dripsharp.pdfcarton.dll"])))))
      (finally
        (delete-tree! root)))))

(deftest release-verification-rejects-symbolic-link-substitution
  (let [[_ inventory] (actual-contract-and-inventory :pdfcube)
        platform
        (first (filter #(= "linux-x64" (:id %))
                       (:platforms inventory)))
        root (temp-directory)
        expected (expected-entry-map inventory platform)
        substitute (zip! (.resolve root "substitute.zip") expected)
        artifact (.resolve root "downloaded.zip")]
    (try
      (Files/createSymbolicLink artifact (.getFileName substitute)
                                (make-array FileAttribute 0))
      (is (= :symbolic-link-release-asset
             (:reason
              (failure
               #(alpha-release/verify-asset!
                 {:artifact artifact
                  :inventory inventory
                  :platform platform
                  :expected-hashes (hashes expected)
                  :framework-assemblies #{"System.Runtime.dll"}})))))
      (finally
        (delete-tree! root)))))

(deftest release-preparation-rejects-manual-product-patches-and-invalid-tags
  (let [[_ inventory] (actual-contract-and-inventory :pkl)
        {:keys [workspace contract product commit]}
        (release-fixture! inventory)
        base-options
        {:workspace-root workspace
         :target-contract contract
         :inventory inventory
         :product-commit commit
         :output-root (paths/resolve-path workspace "release")
         :build-fn (fake-build! (atom []))
         :framework-assemblies #{"System.Runtime.dll"}}]
    (try
      (is (= :invalid-alpha-tag
             (:reason
              (failure
               #(alpha-release/prepare!
                 (assoc base-options
                        :authorized-tag "v0.1.0"))))))
      (write! product
              "src/DripSharp.Brine/Generated.cs"
              "namespace Durable.Manual.Patch;\n")
      (is (= :dirty-product-repository
             (:reason
              (failure
               #(alpha-release/prepare!
                 (assoc base-options
                        :authorized-tag "v0.1.0-alpha.1"))))))
      (git! product "add" "--all")
      (git! product "commit" "-m" "Durable manual generated patch")
      (let [patched-commit (git-output product "rev-parse" "HEAD")
            submodule-path
            (get-in contract [:publication :submodule-path])]
        (git! workspace "update-index" "--cacheinfo"
              (str "160000," patched-commit "," submodule-path))
        (git! workspace "commit" "-m" "Advance to manual product patch")
        (is (= :proved-state-mismatch
               (:reason
                (failure
                 #(alpha-release/prepare!
                   (assoc base-options
                          :authorized-tag "v0.1.0-alpha.1"
                          :product-commit patched-commit)))))))
      (finally
        (delete-tree! workspace)))))

(deftest release-preparation-rejects-symbolic-links-in-proved-state
  (testing "a managed root file cannot redirect hashing"
    (let [[_ inventory] (actual-contract-and-inventory :pkl)
          {:keys [workspace contract staging commit]}
          (release-fixture! inventory)
          staged-readme (paths/resolve-path staging "README.md")
          substitute (write! workspace "substitute-readme.md"
                             "# Generated product\n")]
      (try
        (Files/delete staged-readme)
        (Files/createSymbolicLink staged-readme substitute
                                  (make-array FileAttribute 0))
        (is (= :proved-state-mismatch
               (:reason
                (failure
                 #(alpha-release/prepare!
                   {:workspace-root workspace
                    :target-contract contract
                    :inventory inventory
                    :authorized-tag "v0.1.0-alpha.1"
                    :product-commit commit
                    :output-root (paths/resolve-path workspace "release")
                    :build-fn (fake-build! (atom []))
                    :framework-assemblies #{"System.Runtime.dll"}})))))
        (finally
          (delete-tree! workspace)))))
  (testing "a nested directory link cannot disappear from inventory"
    (let [[_ inventory] (actual-contract-and-inventory :pkl)
          {:keys [workspace contract staging commit]}
          (release-fixture! inventory)
          substitute (paths/resolve-path workspace "substitute-source")
          _ (write! substitute "Hidden.cs" "namespace Hidden;\n")
          link (paths/resolve-path staging "src/substituted")]
      (try
        (Files/createSymbolicLink link substitute
                                  (make-array FileAttribute 0))
        (is (= :proved-state-mismatch
               (:reason
                (failure
                 #(alpha-release/prepare!
                   {:workspace-root workspace
                    :target-contract contract
                    :inventory inventory
                    :authorized-tag "v0.1.0-alpha.1"
                    :product-commit commit
                    :output-root (paths/resolve-path workspace "release")
                    :build-fn (fake-build! (atom []))
                    :framework-assemblies #{"System.Runtime.dll"}})))))
        (finally
          (delete-tree! workspace))))))

(deftest release-preparation-rejects-symbolic-links-in-build-output
  (let [[_ inventory] (actual-contract-and-inventory :pkl)
        {:keys [workspace contract commit]}
        (release-fixture! inventory)
        substitute
        (write! workspace "substitute-release-input.dll"
                "external release input\n")
        linked-file (:entry-assembly inventory)
        build-fn
        (fn [{:keys [inventory platform configuration build-output]}]
          (doseq [file
                  (concat (map :file (:product-assemblies inventory))
                          (map :file (:managed-dependencies inventory))
                          (map :file (:native-assets platform)))]
            (if (= linked-file file)
              (Files/createSymbolicLink (.resolve build-output file)
                                        substitute
                                        (make-array FileAttribute 0))
              (write! build-output file
                      (str (:product-family inventory) "\t"
                           (:id platform) "\t" file "\n"))))
          {:configuration configuration})]
    (try
      (let [result
            (failure
             #(alpha-release/prepare!
               {:workspace-root workspace
                :target-contract contract
                :inventory inventory
                :authorized-tag "v0.1.0-alpha.1"
                :product-commit commit
                :output-root (paths/resolve-path workspace "release")
                :build-fn build-fn
                :framework-assemblies #{"System.Runtime.dll"}}))]
        (is (= :symbolic-link-build-output (:reason result)))
        (is (= [linked-file] (:entries result))))
      (finally
        (delete-tree! workspace)))))

(deftest target-release-workflow-proves-before-local-dry-run-preparation
  (let [calls (atom [])
        result
        (target-execution/prepare-alpha-release!
         {:target :pkl
          :authorized-tag "v0.1.0-alpha.1"
          :platform-ids ["portable"]
          :product-commit
          "0123456789abcdef0123456789abcdef01234567"
          :proof-fn
          (fn [options]
            (swap! calls conj [:proof options])
            :proved)
          :release-fn
          (fn [options]
            (swap! calls conj
                   [:release
                    (select-keys options
                                 [:authorized-tag :platform-ids
                                  :product-commit])])
            :prepared)})]
    (is (= :proved (:proof result)))
    (is (= :prepared (:preparation result)))
    (is (= [[:proof {:workspace-root (paths/workspace-root)
                     :target :pkl}]
            [:release
             {:authorized-tag "v0.1.0-alpha.1"
              :platform-ids ["portable"]
              :product-commit
              "0123456789abcdef0123456789abcdef01234567"}]]
           @calls)))
  (let [proved? (atom false)
        result
        (failure
         #(target-execution/prepare-alpha-release!
           {:target :pkl
            :authorized-tag "v0.1.0"
            :product-commit
            "0123456789abcdef0123456789abcdef01234567"
            :proof-fn (fn [_] (reset! proved? true))
            :release-fn (fn [_] :unexpected)}))]
    (is (= :invalid-alpha-tag (:reason result)))
    (is (false? @proved?)))
  (let [proved? (atom false)
        result
        (failure
         #(target-execution/prepare-alpha-release!
           {:target :pkl
            :authorized-tag "v0.1.0-alpha.1"
            :product-commit
            "0123456789abcdef0123456789abcdef01234567"
            :platform-ids ["osx-x64"]
            :proof-fn (fn [_] (reset! proved? true))
            :release-fn (fn [_] :unexpected)}))]
    (is (= :invalid-release-platform-selection (:reason result)))
    (is (= ["osx-x64"] (:unknown result)))
    (is (false? @proved?))))

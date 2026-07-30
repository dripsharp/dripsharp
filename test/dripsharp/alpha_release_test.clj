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
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption FileVisitOption Files LinkOption OpenOption
            Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Locale]
           [java.util.zip ZipEntry ZipOutputStream]))

(def ^:private json-mapper
  (ObjectMapper.))

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
      (let [assembly (subs file 0 (- (count file) 4))
            project-references
            (when (= file (:entry-assembly inventory))
              (for [{dependency-file :file
                     dependency-project :project}
                    (:product-assemblies inventory)
                    :when (not= file dependency-file)
                    :let [dependency-assembly
                          (subs dependency-file
                                0 (- (count dependency-file) 4))
                          dependency-directory
                          (last (str/split dependency-project #"/"))]]
                (str "<ProjectReference Include=\"../"
                     dependency-directory "/" dependency-assembly
                     ".csproj\" />")))]
        [[(str project "/" assembly ".csproj")
          (str "<Project Sdk=\"Microsoft.NET.Sdk\">"
               "<PropertyGroup><TargetFramework>"
               (:target-framework inventory)
               "</TargetFramework></PropertyGroup>"
               (when (seq project-references)
                 (str "<ItemGroup>"
                      (str/join "" project-references)
                      "</ItemGroup>"))
               "</Project>\n")]
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
  (fn [{:keys [inventory platform configuration build-output packages-root]}]
    (swap! calls conj
           {:platform (:id platform)
            :runtime-identifier (:runtime-identifier platform)
            :configuration configuration})
    (let [content
          (fn [file]
            (str (:product-family inventory) "\t"
                 (:id platform) "\t" file "\n"))]
      (doseq [file
              (concat (map :file (:product-assemblies inventory))
                      (map :file (:managed-dependencies inventory))
                      (map :file (:native-assets platform)))]
        (write! build-output file (content file)))
      (doseq [{:keys [file package-id version]}
              (:managed-dependencies inventory)]
        (write!
         packages-root
         (str (str/lower-case package-id) "/"
              (str/lower-case version) "/lib/"
              (:target-framework inventory) "/" file)
         (content file)))
      (doseq [{:keys [file package-id version package-path]}
              (:native-assets platform)]
        (write!
         packages-root
         (str (str/lower-case package-id) "/"
              (str/lower-case version) "/" package-path)
         (content file))))
    ;; These are normal build byproducts, never selected release inputs.
    (write! build-output "ignored.pdb" "symbols\n")
    (write! build-output "ignored.xml" "documentation\n")
    (write! build-output "ignored.nupkg" "package\n")
    (let [target-name
          (str ".NETCoreApp,Version=v"
               (subs (:target-framework inventory) 3)
               (when-let [runtime-identifier
                          (:runtime-identifier platform)]
                 (str "/" runtime-identifier)))
          managed
          (for [{:keys [file package-id version]}
                (:managed-dependencies inventory)]
            [(str package-id "/" version)
             {"runtime" {(str "lib/" (:target-framework inventory) "/"
                              file)
                         {}}}])
          native
          (for [{:keys [package-id version package-path]}
                (:native-assets platform)]
            [(str package-id "/" version)
             {"native" {package-path {}}}])
          packages (concat managed native)
          library-paths
          (into
           {}
           (for [{:keys [package-id version]}
                 (concat (:managed-dependencies inventory)
                         (:native-assets platform))]
             [(str package-id "/" version)
              (str (str/lower-case package-id) "/"
                   (str/lower-case version))]))
          dependency-file
          (str (subs (:entry-assembly inventory)
                     0 (- (count (:entry-assembly inventory)) 4))
               ".deps.json")]
      (write!
       build-output dependency-file
       (.writeValueAsString
        json-mapper
        {"runtimeTarget" {"name" target-name}
         "targets" {target-name (into {} packages)}
         "libraries"
         (into {}
               (map (fn [[coordinate _]]
                      [coordinate
                       {"type" "package"
                        "path" (get library-paths coordinate)}]))
               packages)})))
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

(deftest release-inventory-rejects-malformed-dependency-package-coordinates
  (let [[contract inventory] (actual-contract-and-inventory :pdfcube)]
    (doseq [package-id ["Bad..Package" "Bad-.Package" "Bad-"]]
      (let [file (str package-id ".dll")
            malformed
            (assoc-in inventory
                      [:managed-dependencies 0]
                      {:file file
                       :package-id package-id
                       :version "1.0.0"})
            result
            (failure
             #(alpha-release/validate-inventory! contract malformed))]
        (is (= :invalid-release-inventory (:reason result)))
        (is (= package-id
               (get-in result [:dependency :package-id])))))
    (doseq [package-id ["Bad Package" "Bad/Package" "Bad..Package" "Bad-"]]
      (let [malformed
            (assoc-in inventory
                      [:platforms 4 :native-assets 0 :package-id]
                      package-id)
            result
            (failure
             #(alpha-release/validate-inventory! contract malformed))]
        (is (= :invalid-release-inventory (:reason result)))
        (is (= package-id (get-in result [:asset :package-id])))))
    (doseq [version ["4.150.1 " "../outside" "1..0" "1.0.0-"
                     "1.0.0+" (str "1." (apply str (repeat 63 "0")))]]
      (doseq [path [[:managed-dependencies 0 :version]
                    [:platforms 4 :native-assets 0 :version]]]
        (let [malformed (assoc-in inventory path version)
              result
              (failure
               #(alpha-release/validate-inventory! contract malformed))]
          (is (= :invalid-release-inventory (:reason result))))))
    (is
     (map?
      (alpha-release/validate-inventory!
       contract
       (-> inventory
           (assoc-in [:managed-dependencies 0 :version]
                     "1.2.3-alpha.1+build-7")
           (assoc-in [:platforms 4 :native-assets 0 :version]
                     "1.2.3-alpha.1+build-7")))))))

(deftest release-request-requires-an-exact-length-product-commit
  (let [authorized-tag "v0.1.0-alpha.1"
        sha1 (apply str (repeat 40 "a"))
        sha256 (apply str (repeat 64 "b"))]
    (is (= sha1
           (:product-commit
            (alpha-release/validate-request! authorized-tag sha1))))
    (is (= sha256
           (:product-commit
            (alpha-release/validate-request! authorized-tag sha256))))
    (doseq [commit
            [(apply str (repeat 39 "a"))
             (apply str (repeat 41 "a"))
             (apply str (repeat 63 "a"))
             (apply str (repeat 65 "a"))
             (apply str (repeat 40 "A"))
             (str (apply str (repeat 39 "a")) "g")]]
      (let [result
            (failure
             #(alpha-release/validate-request! authorized-tag commit))]
        (is (= :invalid-product-commit (:reason result)))
        (is (= commit (:product-commit result)))))))

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

(deftest release-preparation-rejects-symbolic-link-output-roots
  (doseq [linked-path [:leaf :ancestor]]
    (testing (name linked-path)
      (let [[_ inventory] (actual-contract-and-inventory :pkl)
            {:keys [workspace contract commit]}
            (release-fixture! inventory)
            substitute
            (paths/resolve-path workspace
                                (str "substitute-release-" (name linked-path)))
            link (paths/resolve-path workspace
                                     (str "release-" (name linked-path)))
            output-root (if (= :leaf linked-path)
                          link
                          (.resolve link "nested"))
            build-calls (atom [])]
        (try
          (Files/createDirectories substitute
                                   (make-array FileAttribute 0))
          (Files/createSymbolicLink link substitute
                                    (make-array FileAttribute 0))
          (let [result
                (failure
                 #(alpha-release/prepare!
                   {:workspace-root workspace
                    :target-contract contract
                    :inventory inventory
                    :authorized-tag "v0.1.0-alpha.1"
                    :product-commit commit
                    :platform-ids ["portable"]
                    :output-root output-root
                    :build-fn (fake-build! build-calls)
                    :framework-assemblies #{"System.Runtime.dll"}}))]
            (is (= :symbolic-link-release-output (:reason result)))
            (is (= [(str (.relativize workspace link))]
                   (:symbolic-links result)))
            (is (empty? @build-calls))
            (is (empty? (with-open [files (Files/list substitute)]
                          (vec (.toArray files))))))
          (finally
            (delete-tree! workspace)))))))

(deftest release-preparation-rejects-git-metadata-output-roots
  (let [[_ inventory] (actual-contract-and-inventory :pkl)
        {:keys [workspace contract commit]}
        (release-fixture! inventory)]
    (try
      (doseq [relative
              [".git/alpha-release"
               "products/brine/.git/alpha-release"
               "nested/.GIT/alpha-release"]]
        (testing relative
          (let [output-root (paths/resolve-path workspace relative)
                build-calls (atom [])
                result
                (failure
                 #(alpha-release/prepare!
                   {:workspace-root workspace
                    :target-contract contract
                    :inventory inventory
                    :authorized-tag "v0.1.0-alpha.1"
                    :product-commit commit
                    :platform-ids ["portable"]
                    :output-root output-root
                    :build-fn (fake-build! build-calls)
                    :framework-assemblies #{"System.Runtime.dll"}}))]
            (is (= :git-metadata-release-output (:reason result)))
            (is (= [".git"] (:components result)))
            (is (empty? @build-calls))
            (is (not (Files/exists output-root
                                   (make-array LinkOption 0)))))))
      (finally
        (delete-tree! workspace)))))

(deftest release-preparation-rejects-output-root-symlink-substitution
  (let [[_ inventory] (actual-contract-and-inventory :pkl)
        {:keys [workspace contract commit]}
        (release-fixture! inventory)
        substitute (temp-directory)
        output-root (paths/resolve-path workspace "release-output")
        build-calls (atom [])
        build! (fake-build! build-calls)]
    (try
      (let [result
            (failure
             #(alpha-release/prepare!
               {:workspace-root workspace
                :target-contract contract
                :inventory inventory
                :authorized-tag "v0.1.0-alpha.1"
                :product-commit commit
                :platform-ids ["portable"]
                :output-root output-root
                :build-fn
                (fn [build]
                  (let [result (build! build)]
                    (Files/createSymbolicLink
                     output-root substitute (make-array FileAttribute 0))
                    result))
                :framework-assemblies #{"System.Runtime.dll"}}))]
        (is (= :symbolic-link-release-output (:reason result)))
        (is (Files/isSymbolicLink output-root))
        (is (empty? (with-open [files (Files/list substitute)]
                      (vec (.toArray files))))))
      (finally
        (delete-tree! workspace)
        (delete-tree! substitute)))))

(deftest release-preparation-rechecks-output-root-before-record
  (let [[_ inventory] (actual-contract-and-inventory :pkl)
        {:keys [workspace contract commit]}
        (release-fixture! inventory)
        substitute (temp-directory)
        output-root (paths/resolve-path workspace "release-output")
        displaced-root (paths/resolve-path workspace "displaced-output")
        platform (first (:platforms inventory))
        version "0.1.0-alpha.1"
        artifact
        (.resolve output-root
                  (alpha-release/asset-filename inventory version platform))
        record-name
        (str (:asset-prefix inventory) "-" version "-release.edn")
        substituted? (atom false)
        build-calls (atom [])]
    (try
      (let [result
            (failure
             #(alpha-release/prepare!
               {:workspace-root workspace
                :target-contract contract
                :inventory inventory
                :authorized-tag (str "v" version)
                :product-commit commit
                :platform-ids [(:id platform)]
                :output-root output-root
                :run-command!
                (fn [request]
                  (when (and (Files/exists
                              artifact
                              (into-array LinkOption
                                          [LinkOption/NOFOLLOW_LINKS]))
                             (compare-and-set! substituted? false true))
                    (Files/move output-root displaced-root
                                (make-array CopyOption 0))
                    (Files/createSymbolicLink
                     output-root substitute (make-array FileAttribute 0)))
                  (process/run! request))
                :build-fn (fake-build! build-calls)
                :framework-assemblies #{"System.Runtime.dll"}}))]
        (is (= :symbolic-link-release-output (:reason result)))
        (is @substituted?)
        (is (Files/isSymbolicLink output-root))
        (is (Files/exists
             (.resolve displaced-root (.getFileName artifact))
             (make-array LinkOption 0)))
        (is (empty? (with-open [files (Files/list substitute)]
                      (vec (.toArray files)))))
        (is (not (Files/exists
                  (.resolve substitute record-name)
                  (make-array LinkOption 0)))))
      (finally
        (delete-tree! workspace)
        (delete-tree! substitute)))))

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
        (is (= (dissoc first :record-path)
               (util/read-single-edn-string!
                (slurp (:record-path first)))))
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
        (doseq [dependency
                (mapcat
                 (fn [asset]
                   (concat
                    (get-in asset [:dependency-evidence :managed])
                    (get-in asset [:dependency-evidence :native])))
                 (:assets first))]
          (is (string? (:restored-package-path dependency)))
          (is (re-matches #"[0-9a-f]{64}" (:sha256 dependency))))
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

(deftest release-preparation-binds-exact-restored-dependency-evidence
  (let [[_ inventory] (actual-contract-and-inventory :pdfcube)
        {:keys [workspace contract commit]}
        (release-fixture! inventory)
        platform (first (filter #(= "osx-arm64" (:id %))
                                (:platforms inventory)))
        dependency-file
        (str (subs (:entry-assembly inventory)
                   0 (- (count (:entry-assembly inventory)) 4))
             ".deps.json")
        base-build! (fake-build! (atom []))
        cases
        [{:name :missing
          :reason :missing-release-dependency-evidence
          :mutate!
          #(Files/delete
            (paths/resolve-path % dependency-file))}
         {:name :malformed
          :reason :invalid-release-dependency-evidence
          :mutate!
          #(write! % dependency-file "{not-json")}
         {:name :version
          :reason :release-dependency-evidence-mismatch
          :mutate!
          (fn [build-output]
            (let [file (paths/resolve-path build-output dependency-file)]
              (Files/writeString
               file
               (str/replace (Files/readString file)
                            "SkiaSharp/4.150.1"
                            "SkiaSharp/4.151.0")
               StandardCharsets/UTF_8
               (make-array OpenOption 0))))}
         {:name :ambiguous-managed-path
          :reason :release-dependency-evidence-mismatch
          :actual-paths
          ["alternate/net10.0/SkiaSharp.dll"
           "lib/net10.0/SkiaSharp.dll"]
          :mutate!
          (fn [build-output]
            (let [file (paths/resolve-path build-output dependency-file)
                  document
                  (.readValue json-mapper (.toFile file) java.util.Map)
                  target-name (get-in document ["runtimeTarget" "name"])
                  runtime
                  (get-in document
                          ["targets" target-name
                           "SkiaSharp/4.150.1" "runtime"])]
              (.put ^java.util.Map runtime
                    "alternate/net10.0/SkiaSharp.dll"
                    (java.util.HashMap.))
              (.writeValue json-mapper (.toFile file) document)))}
         {:name :native-path
          :reason :release-dependency-evidence-mismatch
          :mutate!
          (fn [build-output]
            (let [file (paths/resolve-path build-output dependency-file)]
              (Files/writeString
               file
               (str/replace (Files/readString file)
                            "runtimes/osx/native/libSkiaSharp.dylib"
                            "runtimes/osx-arm64/native/libSkiaSharp.dylib")
               StandardCharsets/UTF_8
               (make-array OpenOption 0))))}]]
    (try
      (doseq [{:keys [name reason actual-paths mutate!]} cases]
        (testing (clojure.core/name name)
          (let [output-root
                (paths/resolve-path workspace
                                    (str "dependency-evidence-"
                                         (clojure.core/name name)))
                result
                (failure
                 #(alpha-release/prepare!
                   {:workspace-root workspace
                    :target-contract contract
                    :inventory inventory
                    :authorized-tag "v0.1.0-alpha.1"
                    :product-commit commit
                    :platform-ids [(:id platform)]
                    :output-root output-root
                    :build-fn
                    (fn [build]
                      (let [result (base-build! build)]
                        (mutate! (:build-output build))
                        result))
                    :framework-assemblies #{"System.Runtime.dll"}}))]
            (is (= reason (:reason result)))
            (when actual-paths
              (is (= actual-paths (:actual-paths result))))
            (is (not (Files/exists
                      (paths/resolve-path
                       output-root
                       (alpha-release/asset-filename
                        inventory "0.1.0-alpha.1" platform))
                      (make-array LinkOption 0)))))))
      (finally
        (delete-tree! workspace)))))

(deftest release-preparation-binds-dependency-bytes-to-restored-assets
  (let [[_ inventory] (actual-contract-and-inventory :pdfcube)
        {:keys [workspace contract commit]}
        (release-fixture! inventory)
        platform (first (filter #(= "osx-arm64" (:id %))
                                (:platforms inventory)))
        base-build! (fake-build! (atom []))
        managed-restored
        "skiasharp/4.150.1/lib/net10.0/SkiaSharp.dll"
        cases
        [{:name :managed-byte-mismatch
          :reason :release-dependency-byte-mismatch
          :asset-kind :managed
          :mutate!
          (fn [{:keys [build-output]}]
            (write! build-output "SkiaSharp.dll"
                    "substituted managed bytes\n"))}
         {:name :native-byte-mismatch
          :reason :release-dependency-byte-mismatch
          :asset-kind :native
          :mutate!
          (fn [{:keys [build-output]}]
            (write! build-output "libSkiaSharp.dylib"
                    "substituted native bytes\n"))}
         {:name :missing-restored-asset
          :reason :missing-restored-package-asset
          :asset-kind :managed
          :mutate!
          (fn [{:keys [packages-root]}]
            (Files/delete
             (paths/resolve-path packages-root managed-restored)))}
         {:name :linked-restored-asset
          :reason :symbolic-link-restored-package-asset
          :asset-kind :managed
          :mutate!
          (fn [{:keys [build-output packages-root]}]
            (let [restored
                  (paths/resolve-path packages-root managed-restored)
                  output
                  (paths/resolve-path build-output "SkiaSharp.dll")]
              (Files/delete restored)
              (Files/createSymbolicLink
               restored
               (.relativize (.getParent restored) output)
               (make-array FileAttribute 0))))}
         {:name :escaped-library-path
          :reason :release-dependency-evidence-mismatch
          :asset-kind :managed
          :mutate!
          (fn [{:keys [build-output]}]
            (let [dependency-file
                  (paths/resolve-path
                   build-output "DripSharp.PdfCarton.Preflight.deps.json")
                  document
                  (.readValue json-mapper (.toFile dependency-file)
                              java.util.Map)]
              (.put
               ^java.util.Map
               (get document "libraries")
               "SkiaSharp/4.150.1"
               {"type" "package"
                "path" "../../outside"})
              (.writeValue json-mapper (.toFile dependency-file)
                           document)))}]]
    (try
      (doseq [{:keys [name reason asset-kind mutate!]} cases]
        (testing (clojure.core/name name)
          (let [output-root
                (paths/resolve-path
                 workspace
                 (str "dependency-bytes-" (clojure.core/name name)))
                result
                (failure
                 #(alpha-release/prepare!
                   {:workspace-root workspace
                    :target-contract contract
                    :inventory inventory
                    :authorized-tag "v0.1.0-alpha.1"
                    :product-commit commit
                    :platform-ids [(:id platform)]
                    :output-root output-root
                    :build-fn
                    (fn [build]
                      (let [result (base-build! build)]
                        (mutate! build)
                        result))
                    :framework-assemblies #{"System.Runtime.dll"}}))]
            (is (= reason (:reason result)))
            (is (= asset-kind (:asset-kind result)))
            (is (not
                 (Files/exists
                  (paths/resolve-path
                   output-root
                   (alpha-release/asset-filename
                    inventory "0.1.0-alpha.1" platform))
                  (make-array LinkOption 0)))))))
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

(deftest default-release-build-keeps-product-checkout-clean
  (let [[_ inventory] (actual-contract-and-inventory :pkl)
        {:keys [workspace contract product commit]}
        (release-fixture! inventory)
        commands (atom [])
        run-command!
        (fn [request]
          (swap! commands conj (:command request))
          (process/run! request))]
    (try
      (write!
       workspace "Directory.Build.props"
       (str "<Project><Target Name=\"RejectUnprovedAncestorProps\" "
            "BeforeTargets=\"Build\"><Error Text=\"Unproved ancestor "
            "Directory.Build.props was imported\" /></Target></Project>\n"))
      (write!
       workspace "Directory.Build.targets"
       (str "<Project><Target Name=\"RejectUnprovedAncestorTargets\" "
            "BeforeTargets=\"Build\"><Error Text=\"Unproved ancestor "
            "Directory.Build.targets was imported\" /></Target></Project>\n"))
      (let [prepared
            (alpha-release/prepare!
             {:workspace-root workspace
              :target-contract contract
              :inventory inventory
              :authorized-tag "v0.1.0-alpha.1"
              :product-commit commit
              :output-root (paths/resolve-path workspace "release")
              :run-command! run-command!
              :framework-assemblies #{"System.Runtime.dll"}})
            dotnet-command
            (first (filter #(= ["dotnet" "build"] (subvec % 0 2))
                           @commands))
            artifacts-index (.indexOf dotnet-command "--artifacts-path")
            artifacts-path
            (paths/absolute (nth dotnet-command (inc artifacts-index)))
            packages-option
            (first
             (filter #(str/starts-with?
                       % "-p:RestorePackagesPath=")
                     dotnet-command))
            packages-path
            (paths/absolute
             (subs packages-option
                   (count "-p:RestorePackagesPath=")))]
        (is (= #{"DripSharp.Brine.dll"
                 "DripSharp.Brine.Parser.dll"}
               (set (keys (:entries (first (:assets prepared)))))))
        (is (not (neg? artifacts-index)))
        (is (not (.startsWith artifacts-path (paths/absolute product))))
        (is (not (.startsWith packages-path (paths/absolute product))))
        (is (some #{"-p:ImportDirectoryBuildProps=false"} dotnet-command))
        (is (some #{"-p:ImportDirectoryBuildTargets=false"} dotnet-command))
        (is (str/blank?
             (git-output product "status" "--porcelain=v1"
                         "--untracked-files=all"))))
      (finally
        (delete-tree! workspace)))))

(deftest release-preparation-rejects-committed-build-directories
  (testing "an unproved committed file below bin is not ignored"
    (let [[_ inventory] (actual-contract-and-inventory :pkl)
          {:keys [workspace contract product]}
          (release-fixture! inventory)
          project (:project (first (:product-assemblies inventory)))
          injected (str project "/bin/Injected.cs")]
      (try
        (write! product injected "namespace Unproved.Committed.File;\n")
        (git! product "add" "--all")
        (git! product "commit" "-m" "Add unproved committed build file")
        (let [patched-commit (git-output product "rev-parse" "HEAD")
              submodule-path
              (get-in contract [:publication :submodule-path])]
          (git! workspace "update-index" "--cacheinfo"
                (str "160000," patched-commit "," submodule-path))
          (git! workspace "commit" "-m" "Pin unproved product commit")
          (let [result
                (failure
                 #(alpha-release/prepare!
                   {:workspace-root workspace
                    :target-contract contract
                    :inventory inventory
                    :authorized-tag "v0.1.0-alpha.1"
                    :product-commit patched-commit
                    :output-root (paths/resolve-path workspace "release")
                    :build-fn (fake-build! (atom []))
                    :framework-assemblies #{"System.Runtime.dll"}}))]
            (is (= :proved-state-mismatch (:reason result)))
            (is (= [injected] (:unexpected result)))))
        (finally
          (delete-tree! workspace)))))
  (testing "ephemeral staging build output remains excluded"
    (let [[_ inventory] (actual-contract-and-inventory :pkl)
          {:keys [workspace contract staging commit]}
          (release-fixture! inventory)
          project (:project (first (:product-assemblies inventory)))]
      (try
        (write! staging (str project "/obj/project.assets.json")
                "ephemeral restore output\n")
        (let [prepared
              (alpha-release/prepare!
               {:workspace-root workspace
                :target-contract contract
                :inventory inventory
                :authorized-tag "v0.1.0-alpha.1"
                :product-commit commit
                :output-root (paths/resolve-path workspace "release")
                :build-fn (fake-build! (atom []))
                :framework-assemblies #{"System.Runtime.dll"}})]
          (is (= ["portable"] (:platforms prepared))))
        (finally
          (delete-tree! workspace))))))

(deftest release-preparation-does-not-overwrite-existing-records
  (let [[_ inventory] (actual-contract-and-inventory :pkl)
        {:keys [workspace contract commit]}
        (release-fixture! inventory)
        record-name
        "DripSharp.Brine-0.1.0-alpha.1-release.edn"
        build-calls (atom [])
        options
        {:workspace-root workspace
         :target-contract contract
         :inventory inventory
         :authorized-tag "v0.1.0-alpha.1"
         :product-commit commit
         :build-fn (fake-build! build-calls)
         :framework-assemblies #{"System.Runtime.dll"}}]
    (try
      (testing "a regular preparation record is preserved"
        (let [output-root (paths/resolve-path workspace "regular-record")
              record (write! output-root record-name "prior record\n")
              result
              (failure
               #(alpha-release/prepare!
                 (assoc options :output-root output-root)))]
          (is (= :release-output-exists (:reason result)))
          (is (= (str record) (:path result)))
          (is (= "prior record\n" (slurp (str record))))
          (is (empty? @build-calls))))
      (testing "a symbolic-link preparation record is preserved"
        (let [output-root (paths/resolve-path workspace "linked-record")
              substitute (write! workspace "substitute-record.edn"
                                 "external record\n")
              record (paths/resolve-path output-root record-name)
              _ (Files/createDirectories output-root
                                         (make-array FileAttribute 0))
              _ (Files/createSymbolicLink
                 record substitute (make-array FileAttribute 0))
              result
              (failure
               #(alpha-release/prepare!
                 (assoc options :output-root output-root)))]
          (is (= :release-output-exists (:reason result)))
          (is (= (str record) (:path result)))
          (is (Files/isSymbolicLink record))
          (is (= "external record\n" (slurp (str substitute))))
          (is (empty? @build-calls))))
      (finally
        (delete-tree! workspace)))))

(deftest release-preparation-preflights-every-selected-output
  (let [[_ inventory] (actual-contract-and-inventory :pdfcube)
        selected-platforms (subvec (:platforms inventory) 0 2)
        platform-ids (mapv :id selected-platforms)
        version "0.1.0-alpha.1"
        first-asset
        (alpha-release/asset-filename
         inventory version (first selected-platforms))
        conflicting-asset
        (alpha-release/asset-filename
         inventory version (second selected-platforms))
        record-name
        (str (:asset-prefix inventory) "-" version "-release.edn")
        {:keys [workspace contract commit]}
        (release-fixture! inventory)
        build-calls (atom [])
        options
        {:workspace-root workspace
         :target-contract contract
         :inventory inventory
         :authorized-tag (str "v" version)
         :product-commit commit
         :platform-ids platform-ids
         :build-fn (fake-build! build-calls)
         :framework-assemblies #{"System.Runtime.dll"}}]
    (try
      (testing "a regular later-platform asset is preserved"
        (let [output-root (paths/resolve-path workspace "regular-asset")
              conflict (write! output-root conflicting-asset "prior asset\n")
              result
              (failure
               #(alpha-release/prepare!
                 (assoc options :output-root output-root)))]
          (is (= :release-output-exists (:reason result)))
          (is (= (str conflict) (:path result)))
          (is (= "prior asset\n" (slurp (str conflict))))
          (is (empty? @build-calls))
          (is (not (Files/exists (.resolve output-root first-asset)
                                 (make-array java.nio.file.LinkOption 0))))
          (is (not (Files/exists (.resolve output-root record-name)
                                 (make-array java.nio.file.LinkOption 0))))))
      (testing "a symbolic-link later-platform asset is preserved"
        (let [output-root (paths/resolve-path workspace "linked-asset")
              substitute (write! workspace "substitute-asset.zip"
                                 "external asset\n")
              conflict (.resolve output-root conflicting-asset)
              _ (Files/createDirectories output-root
                                         (make-array FileAttribute 0))
              _ (Files/createSymbolicLink
                 conflict substitute (make-array FileAttribute 0))
              result
              (failure
               #(alpha-release/prepare!
                 (assoc options :output-root output-root)))]
          (is (= :release-output-exists (:reason result)))
          (is (= (str conflict) (:path result)))
          (is (Files/isSymbolicLink conflict))
          (is (= "external asset\n" (slurp (str substitute))))
          (is (empty? @build-calls))
          (is (not (Files/exists (.resolve output-root first-asset)
                                 (make-array java.nio.file.LinkOption 0))))
          (is (not (Files/exists (.resolve output-root record-name)
                                 (make-array java.nio.file.LinkOption 0))))))
      (finally
        (delete-tree! workspace)))))

(deftest failed-release-preparation-rolls-back-created-assets
  (let [[_ inventory] (actual-contract-and-inventory :pdfcube)
        selected-platforms (subvec (:platforms inventory) 0 2)
        platform-ids (mapv :id selected-platforms)
        version "0.1.0-alpha.1"
        asset-names
        (mapv #(alpha-release/asset-filename inventory version %)
              selected-platforms)
        record-name
        (str (:asset-prefix inventory) "-" version "-release.edn")
        {:keys [workspace contract commit staging]}
        (release-fixture! inventory)
        options
        {:workspace-root workspace
         :target-contract contract
         :inventory inventory
         :authorized-tag (str "v" version)
         :product-commit commit
         :platform-ids platform-ids
         :framework-assemblies #{"System.Runtime.dll"}}]
    (try
      (testing "a later build failure removes an earlier completed ZIP"
        (let [output-root (paths/resolve-path workspace "failed-build")
              calls (atom [])
              build! (fake-build! calls)
              result
              (failure
               #(alpha-release/prepare!
                 (assoc
                  options
                  :output-root output-root
                  :build-fn
                  (fn [build]
                    (if (= (second platform-ids)
                           (get-in build [:platform :id]))
                      (throw
                       (ex-info "Synthetic later-platform build failure"
                                {:kind :synthetic-build-failure
                                 :reason :synthetic-build-failure}))
                      (build! build))))))]
          (is (= :synthetic-build-failure (:reason result)))
          (is (= [(first platform-ids)]
                 (mapv :platform @calls)))
          (doseq [asset asset-names]
            (is (not (Files/exists
                      (.resolve output-root asset)
                      (make-array LinkOption 0)))))
          (is (not (Files/exists
                    (.resolve output-root record-name)
                    (make-array LinkOption 0))))))
      (testing "a later output race is preserved while earlier output rolls back"
        (let [output-root (paths/resolve-path workspace "racing-output")
              conflicting-asset (.resolve output-root (second asset-names))
              calls (atom [])
              build! (fake-build! calls)
              result
              (failure
               #(alpha-release/prepare!
                 (assoc
                  options
                  :output-root output-root
                  :build-fn
                  (fn [build]
                    (let [result (build! build)]
                      (when (= (second platform-ids)
                               (get-in build [:platform :id]))
                        (write! output-root (second asset-names)
                                "concurrent output\n"))
                      result)))))]
          (is (= :release-output-exists (:reason result)))
          (is (= (str conflicting-asset) (:path result)))
          (is (= "concurrent output\n" (slurp (str conflicting-asset))))
          (is (not (Files/exists
                    (.resolve output-root (first asset-names))
                    (make-array LinkOption 0))))
          (is (not (Files/exists
                    (.resolve output-root record-name)
                    (make-array LinkOption 0))))))
      (testing "a replaced earlier output is preserved during rollback"
        (let [output-root (paths/resolve-path workspace "replaced-output")
              replaced-asset (.resolve output-root (first asset-names))
              replacement "replacement owned by another actor\n"
              calls (atom [])
              build! (fake-build! calls)
              result
              (failure
               #(alpha-release/prepare!
                 (assoc
                  options
                  :output-root output-root
                  :build-fn
                  (fn [build]
                    (if (= (second platform-ids)
                           (get-in build [:platform :id]))
                      (do
                        (write! output-root (first asset-names) replacement)
                        (throw
                         (ex-info "Synthetic failure after replacement"
                                  {:kind :synthetic-build-failure
                                   :reason :synthetic-build-failure})))
                      (build! build))))))]
          (is (= :synthetic-build-failure (:reason result)))
          (is (= replacement (slurp (str replaced-asset))))
          (is (not (Files/exists
                    (.resolve output-root (second asset-names))
                    (make-array LinkOption 0))))
          (is (not (Files/exists
                    (.resolve output-root record-name)
                    (make-array LinkOption 0))))))
      (testing "a changed earlier output prevents a successful record"
        (let [output-root (paths/resolve-path workspace "changed-output")
              changed-asset (.resolve output-root (first asset-names))
              replacement "replacement owned by another actor\n"
              calls (atom [])
              build! (fake-build! calls)
              result
              (failure
               #(alpha-release/prepare!
                 (assoc
                  options
                  :output-root output-root
                  :build-fn
                  (fn [build]
                    (let [result (build! build)]
                      (when (= (second platform-ids)
                               (get-in build [:platform :id]))
                        (write! output-root (first asset-names) replacement))
                      result)))))]
          (is (= :prepared-release-asset-changed (:reason result)))
          (is (= [(str changed-asset)] (:paths result)))
          (is (= replacement (slurp (str changed-asset))))
          (is (not (Files/exists
                    (.resolve output-root (second asset-names))
                    (make-array LinkOption 0))))
          (is (not (Files/exists
                    (.resolve output-root record-name)
                    (make-array LinkOption 0))))))
      (testing "a final proved-state failure removes every completed ZIP"
        (let [output-root (paths/resolve-path workspace "failed-final-proof")
              managed-path (ffirst (generated-files inventory))
              calls (atom [])
              build! (fake-build! calls)
              result
              (failure
               #(alpha-release/prepare!
                 (assoc
                  options
                  :output-root output-root
                  :build-fn
                  (fn [build]
                    (let [result (build! build)]
                      (when (= (second platform-ids)
                               (get-in build [:platform :id]))
                        (write! staging managed-path
                                "changed after release builds\n"))
                      result)))))]
          (is (= :proved-state-mismatch (:reason result)))
          (doseq [asset asset-names]
            (is (not (Files/exists
                      (.resolve output-root asset)
                      (make-array LinkOption 0)))))
          (is (not (Files/exists
                    (.resolve output-root record-name)
                    (make-array LinkOption 0))))))
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
                     {:artifact-root root
                      :artifact asset
                      :expected-artifact-sha256 (util/sha256-file asset)
                      :inventory inventory
                      :platform platform
                      :expected-hashes (hashes expected)
                      :framework-assemblies #{"System.Runtime.dll"}}))))))))
      (testing "downloaded ZIPs must match the prepared archive checksum"
        (let [prepared
              (zip! (.resolve root "prepared-checksum.zip") expected)
              downloaded
              (zip! (.resolve root "downloaded-checksum.zip")
                    (reverse (seq expected)))
              prepared-sha256 (util/sha256-file prepared)
              downloaded-sha256 (util/sha256-file downloaded)
              result
              (failure
               #(alpha-release/verify-asset!
                 {:artifact-root root
                  :artifact downloaded
                  :expected-artifact-sha256 prepared-sha256
                  :inventory inventory
                  :platform platform
                  :expected-hashes (hashes expected)
                  :framework-assemblies #{"System.Runtime.dll"}}))]
          (is (not= prepared-sha256 downloaded-sha256))
          (is (= :release-artifact-digest-mismatch (:reason result)))
          (is (= prepared-sha256 (:expected result)))
          (is (= downloaded-sha256 (:actual result)))))
      (testing "downloaded verification requires an exact prepared checksum"
        (let [asset (zip! (.resolve root "missing-checksum.zip") expected)]
          (doseq [digest [nil "ABC"]]
            (let [result
                  (failure
                   #(alpha-release/verify-asset!
                     {:artifact-root root
                      :artifact asset
                      :expected-artifact-sha256 digest
                      :inventory inventory
                      :platform platform
                      :expected-hashes (hashes expected)
                      :framework-assemblies #{"System.Runtime.dll"}}))]
              (is (= :invalid-release-artifact-digest (:reason result)))
              (is (= digest (:expected-artifact-sha256 result)))))))
      (testing "forbidden release payloads fail before unrelated-file handling"
        (doseq [file ["leaked.nupkg" "leaked.pdb" "leaked.xml"
                      "source.zip" "source.tar.gz"]]
          (let [entries (assoc expected file "forbidden")
                asset (zip! (.resolve root (str file ".asset")) entries)]
            (is (= :forbidden-release-file
                   (:reason
                    (failure
                     #(alpha-release/verify-asset!
                       {:artifact-root root
                        :artifact asset
                        :expected-artifact-sha256 (util/sha256-file asset)
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
                 {:artifact-root root
                  :artifact asset
                  :expected-artifact-sha256 (util/sha256-file asset)
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
                 {:artifact-root root
                  :artifact asset
                  :expected-artifact-sha256 (util/sha256-file asset)
                  :inventory inventory
                  :platform platform
                  :expected-hashes (hashes entries)
                  :framework-assemblies #{"System.Runtime.dll"}}))]
          (is (= :release-asset-mismatch (:reason result)))
          (is (= ["Shadow.dll"] (:unexpected-managed result)))
          (is (= ["libShadow.so"] (:unexpected-native result)))
          (is (= ["README.md"] (:unrelated result)))))
      (testing "framework assemblies fail even when they are otherwise unexpected"
        (doseq [file ["System.Runtime.dll" "system.runtime.dll"]]
          (let [entries (assoc expected file "framework")
                asset (zip! (.resolve root (str file ".asset")) entries)
                result
                (failure
                 #(alpha-release/verify-asset!
                   {:artifact-root root
                    :artifact asset
                    :expected-artifact-sha256 (util/sha256-file asset)
                    :inventory inventory
                    :platform platform
                    :expected-hashes (hashes entries)
                    :framework-assemblies
                    #{"System.Runtime.dll"}}))]
            (is (= :framework-assembly (:reason result)))
            (is (= [file] (:entries result))))))
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
      (testing "case-insensitive release checks do not depend on the JVM locale"
        (let [default-locale (Locale/getDefault)]
          (try
            (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
            (let [colliding
                  (update
                   inventory :managed-dependencies conj
                   {:file "SKIASHARP.dll"
                    :package-id "SKIASHARP"
                    :version "1.0.0"})
                  inventory-result
                  (failure
                   #(alpha-release/validate-inventory!
                     contract colliding))
                  entries (assoc expected
                                 "SKIASHARP.dll"
                                 "locale-sensitive collision")
                  asset (zip! (.resolve root "locale-collision.asset")
                              entries)
                  asset-result
                  (failure
                   #(alpha-release/verify-asset!
                     {:artifact-root root
                      :artifact asset
                      :expected-artifact-sha256 (util/sha256-file asset)
                      :inventory inventory
                      :platform platform
                      :expected-hashes (hashes entries)
                      :framework-assemblies #{"System.Runtime.dll"}}))
                  framework-entries
                  (assoc expected
                         "SYSTEM.RUNTIME.DLL"
                         "locale-sensitive framework assembly")
                  framework-asset
                  (zip! (.resolve root "locale-framework.asset")
                        framework-entries)
                  framework-result
                  (failure
                   #(alpha-release/verify-asset!
                     {:artifact-root root
                      :artifact framework-asset
                      :expected-artifact-sha256
                      (util/sha256-file framework-asset)
                      :inventory inventory
                      :platform platform
                      :expected-hashes (hashes framework-entries)
                      :framework-assemblies #{"System.Runtime.dll"}}))]
              (is (= :dependency-name-collision
                     (:reason inventory-result)))
              (is (= #{"SKIASHARP.dll" "SkiaSharp.dll"}
                     (set
                      (map :file
                           (get-in inventory-result
                                   [:collisions "skiasharp.dll"])))))
              (is (= :dependency-name-collision
                     (:reason asset-result)))
              (is (= ["SKIASHARP.dll" "SkiaSharp.dll"]
                     (get-in asset-result
                             [:collisions "skiasharp.dll"])))
              (is (= :framework-assembly
                     (:reason framework-result)))
              (is (= ["SYSTEM.RUNTIME.DLL"]
                     (:entries framework-result))))
            (finally
              (Locale/setDefault default-locale)))))
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
                 {:artifact-root root
                  :artifact asset
                  :expected-artifact-sha256 (util/sha256-file asset)
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
        substitute-root (.resolve root "substitute")
        substitute
        (zip! (.resolve substitute-root "downloaded.zip") expected)
        artifact (.resolve root "downloaded.zip")
        linked-root (.resolve root "linked-download")
        linked-artifact (.resolve linked-root "downloaded.zip")]
    (try
      (Files/createSymbolicLink artifact (.relativize root substitute)
                                (make-array FileAttribute 0))
      (Files/createSymbolicLink linked-root (.getFileName substitute-root)
                                (make-array FileAttribute 0))
      (doseq [[candidate expected-links]
              [[artifact [(str artifact)]]
               [linked-artifact [(str linked-root)]]]]
        (let [result
              (failure
               #(alpha-release/verify-asset!
                 {:artifact-root root
                  :artifact candidate
                  :expected-artifact-sha256 (util/sha256-file substitute)
                  :inventory inventory
                  :platform platform
                  :expected-hashes (hashes expected)
                  :framework-assemblies #{"System.Runtime.dll"}}))]
          (is (= :symbolic-link-release-asset (:reason result)))
          (is (= (str candidate) (:path result)))
          (is (= expected-links (:symbolic-links result)))))
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

(deftest release-preparation-rejects-case-variant-framework-dependencies
  (let [[_ base-inventory] (actual-contract-and-inventory :pkl)
        inventory
        (update base-inventory :managed-dependencies conj
                {:file "system.runtime.dll"
                 :package-id "system.runtime"
                 :version "10.0.0"})
        {:keys [workspace contract commit]}
        (release-fixture! inventory)]
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
                :build-fn (fake-build! (atom []))
                :framework-assemblies #{"System.Runtime.dll"}}))]
        (is (= :framework-assembly (:reason result)))
        (is (= ["system.runtime.dll"] (:files result))))
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

(deftest release-preparation-rejects-symbolic-link-ancestors-of-managed-paths
  (doseq [path-key [:staging :product]]
    (testing (name path-key)
      (let [[_ inventory] (actual-contract-and-inventory :pkl)
            {:keys [workspace contract product] :as fixture}
            (release-fixture! inventory)
            managed-path (:project (first (:product-assemblies inventory)))
            managed-root (get fixture path-key)
            linked-ancestor (.getParent
                             (paths/resolve-path managed-root managed-path))
            substitute
            (paths/resolve-path workspace
                                (str "substitute-managed-" (name path-key)))]
        (try
          (Files/move linked-ancestor substitute (make-array CopyOption 0))
          (Files/createSymbolicLink linked-ancestor substitute
                                    (make-array FileAttribute 0))
          (let [product-commit
                (if (= :product path-key)
                  (do
                    (git! product "add" "--all")
                    (git! product "commit" "-m"
                          "Substitute managed path ancestor")
                    (let [commit (git-output product "rev-parse" "HEAD")
                          submodule-path
                          (get-in contract [:publication :submodule-path])]
                      (git! workspace "update-index" "--cacheinfo"
                            (str "160000," commit "," submodule-path))
                      (git! workspace "commit" "-m"
                            "Pin substituted managed path")
                      commit))
                  (:commit fixture))
                result
                (failure
                 #(alpha-release/prepare!
                   {:workspace-root workspace
                    :target-contract
                    (assoc-in contract [:publication :managed-paths]
                              [managed-path])
                    :inventory inventory
                    :authorized-tag "v0.1.0-alpha.1"
                    :product-commit product-commit
                    :output-root (paths/resolve-path workspace "release")
                    :build-fn (fake-build! (atom []))
                    :framework-assemblies #{"System.Runtime.dll"}}))]
            (is (= :proved-state-mismatch (:reason result)))
            (is (= managed-path (:path result)))
            (is (= [(str (.relativize managed-root linked-ancestor))]
                   (:symbolic-links result))))
          (finally
            (delete-tree! workspace)))))))

(deftest release-preparation-rejects-symbolic-links-at-proved-path-boundaries
  (doseq [{:keys [subject path-key linked-path]}
          [{:subject "Proved product staging"
            :path-key :staging
            :linked-path :leaf}
           {:subject "Proved product staging"
            :path-key :staging
            :linked-path :parent}
           {:subject "Product submodule"
            :path-key :product
            :linked-path :leaf}]]
    (testing (str subject " " (name linked-path))
      (let [[_ inventory] (actual-contract-and-inventory :pkl)
            {:keys [workspace contract commit] :as fixture}
            (release-fixture! inventory)
            contracted-path (get fixture path-key)
            link (if (= :parent linked-path)
                   (.getParent contracted-path)
                   contracted-path)
            substitute
            (paths/resolve-path
             workspace
             (str "substitute-" (name path-key) "-" (name linked-path)))]
        (try
          (Files/move link substitute (make-array CopyOption 0))
          (Files/createSymbolicLink
           link substitute (make-array FileAttribute 0))
          (let [result
                (failure
                 #(alpha-release/prepare!
                   {:workspace-root workspace
                    :target-contract contract
                    :inventory inventory
                    :authorized-tag "v0.1.0-alpha.1"
                    :product-commit commit
                    :output-root (paths/resolve-path workspace "release")
                    :build-fn (fake-build! (atom []))
                    :framework-assemblies #{"System.Runtime.dll"}}))]
            (is (= :symbolic-link-release-path (:reason result)))
            (is (= subject (:subject result)))
            (is (= [(str (.relativize workspace link))]
                   (:symbolic-links result))))
          (finally
            (delete-tree! workspace)))))))

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

(deftest release-preparation-rejects-symbolic-link-build-output-ancestors
  (let [[_ inventory] (actual-contract-and-inventory :pkl)
        {:keys [workspace contract commit]}
        (release-fixture! inventory)
        substitute-root
        (paths/resolve-path workspace "substitute-platform-build")
        build-fn
        (fn [{:keys [inventory platform configuration build-output]}]
          (let [platform-root (.getParent ^Path build-output)
                substitute-output
                (paths/resolve-path substitute-root "output")]
            (Files/delete build-output)
            (Files/delete platform-root)
            (Files/createDirectories
             substitute-output (make-array FileAttribute 0))
            (Files/createSymbolicLink
             platform-root substitute-root (make-array FileAttribute 0))
            (doseq [file
                    (concat (map :file (:product-assemblies inventory))
                            (map :file (:managed-dependencies inventory))
                            (map :file (:native-assets platform)))]
              (write! build-output file
                      (str (:product-family inventory) "\t"
                           (:id platform) "\t" file "\n")))
            {:configuration configuration}))]
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
        (is (= "portable" (:platform result)))
        (is (= ["portable"] (:symbolic-links result))))
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
        product-commit (apply str (repeat 41 "a"))
        result
        (failure
         #(target-execution/prepare-alpha-release!
           {:target :pkl
            :authorized-tag "v0.1.0-alpha.1"
            :product-commit product-commit
            :proof-fn (fn [_] (reset! proved? true))
            :release-fn (fn [_] :unexpected)}))]
    (is (= :invalid-product-commit (:reason result)))
    (is (= product-commit (:product-commit result)))
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

(ns vibeformer.destination
  (:require [clojure.string :as str])
  (:import (java.nio.file Path Paths)))

(def default-target-framework "net8.0")
(def default-sdk "Microsoft.NET.Sdk")
(def default-output-type "Library")
(def default-implicit-usings "disable")
(def default-nullable "enable")

(defn- path [value]
  (if (instance? Path value)
    value
    (Paths/get (str value) (make-array String 0))))

(defn normalize-path [value]
  (.normalize (.toAbsolutePath (path value))))

(defn slash-path [value]
  (str/replace (str value) \\ \/))

(defn relative-slash-path [^Path root value]
  (slash-path (.relativize (.normalize root) (.normalize (path value)))))

(defn xml-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn csharp-project-file [sample target-csharp-dir]
  (.resolve (path target-csharp-dir) (str (:sample/name sample) ".csproj")))

(defn sample-project-map
  [{:keys [sample target-csharp-dir project-file csharp-files helper-files target-framework]}]
  (let [target-csharp-dir (path target-csharp-dir)
        project-file (or project-file (csharp-project-file sample target-csharp-dir))
        project-id (str (:sample/name sample) ":csharp")
        helper-paths (->> helper-files
                          (map :helper/project-path)
                          set)
        compile-items (->> csharp-files
                           (mapv (fn [file]
                                   (let [relative-path (relative-slash-path target-csharp-dir file)
                                         helper? (contains? helper-paths relative-path)]
                                     {:dest.item/id (str project-id
                                                         (if helper? ":helper:" ":compile:")
                                                         relative-path)
                                      :dest.item/kind (if helper?
                                                        :dest.item.kind/helper
                                                        :dest.item.kind/compile)
                                      :dest.item/path relative-path})))
                           (sort-by :dest.item/path)
                           vec)]
    {:dest.project/id project-id
     :dest.project/source-module (:sample/name sample)
     :dest.project/name (:sample/name sample)
     :dest.project/path (slash-path (normalize-path project-file))
     :dest.project/sdk default-sdk
     :dest.project/output-type default-output-type
     :dest.project/target-framework (or target-framework default-target-framework)
     :dest.project/implicit-usings default-implicit-usings
     :dest.project/nullable default-nullable
     :dest.project/default-compile-items? false
     :dest.project/items compile-items
     :dest.project/dependencies []}))

(defn sample-project-facts [project-id project-map]
  [(assoc project-map
          :db/id (:dest.project/id project-map)
          :dest.project/source-project [:project/id project-id]
          :dest.project/items (mapv #(assoc % :db/id (:dest.item/id %))
                                    (:dest.project/items project-map))
          :dest.project/dependencies (mapv #(assoc % :db/id (:dest.dependency/id %))
                                           (:dest.project/dependencies project-map)))])

(defn project->artifact [project-map]
  (-> project-map
      (update :dest.project/items #(mapv (fn [item]
                                           (select-keys item [:dest.item/kind
                                                              :dest.item/path]))
                                         %))
      (update :dest.project/dependencies #(mapv (fn [dependency]
                                                  (select-keys dependency [:dest.dependency/kind
                                                                           :dest.dependency/include
                                                                           :dest.dependency/version
                                                                           :dest.dependency/path
                                                                           :dest.dependency/source-configuration
                                                                           :dest.dependency/source-expression]))
                                                %))))

(defn csharp-project-content [project-map]
  (let [compile-items (->> (:dest.project/items project-map)
                           (filter #(contains? #{:dest.item.kind/compile
                                                 :dest.item.kind/helper}
                                               (:dest.item/kind %)))
                           (sort-by :dest.item/path)
                           (map #(str "    <Compile Include=\""
                                      (xml-escape (:dest.item/path %))
                                      "\" />\n"))
                           (apply str))
        project-references (->> (:dest.project/dependencies project-map)
                                (filter #(= :dest.dependency.kind/project-reference
                                            (:dest.dependency/kind %)))
                                (sort-by :dest.dependency/path)
                                (map #(str "    <ProjectReference Include=\""
                                           (xml-escape (:dest.dependency/path %))
                                           "\" />\n"))
                                (apply str))
        package-references (->> (:dest.project/dependencies project-map)
                                (filter #(= :dest.dependency.kind/package
                                            (:dest.dependency/kind %)))
                                (sort-by (juxt :dest.dependency/include
                                               :dest.dependency/version))
                                (map #(str "    <PackageReference Include=\""
                                           (xml-escape (:dest.dependency/include %))
                                           "\""
                                           (when-let [version (:dest.dependency/version %)]
                                             (str " Version=\"" (xml-escape version) "\""))
                                           " />\n"))
                                (apply str))
        item-groups (remove str/blank? [compile-items project-references package-references])]
    (str "<Project Sdk=\"" (xml-escape (:dest.project/sdk project-map)) "\">\n"
         "  <PropertyGroup>\n"
         "    <OutputType>" (xml-escape (:dest.project/output-type project-map)) "</OutputType>\n"
         "    <TargetFramework>" (xml-escape (:dest.project/target-framework project-map)) "</TargetFramework>\n"
         "    <ImplicitUsings>" (xml-escape (:dest.project/implicit-usings project-map)) "</ImplicitUsings>\n"
         "    <Nullable>" (xml-escape (:dest.project/nullable project-map)) "</Nullable>\n"
         "    <EnableDefaultCompileItems>" (if (:dest.project/default-compile-items? project-map)
                                             "true"
                                             "false")
         "</EnableDefaultCompileItems>\n"
         "  </PropertyGroup>\n"
         (apply str
                (map (fn [items]
                       (str "\n"
                            "  <ItemGroup>\n"
                            items
                            "  </ItemGroup>\n"))
                     item-groups))
         "</Project>\n")))

(defn- csharp-project-name [project-id project-path]
  (let [path-name (-> project-path
                      (str/replace #"^:+" "")
                      (str/replace #":" "."))
        path-name (if (str/blank? path-name) "root" path-name)]
    (str project-id "." path-name)))

(defn- catalog-by-alias [classpath-report]
  (->> (get-in classpath-report [:version-catalog :catalog/libraries])
       (map (juxt :catalog/alias identity))
       (into {})))

(defn- package-reference [project-id project-path index dependency catalog]
  (let [library (get catalog (:dependency/catalog-alias dependency))
        include (or (:dependency/coordinate dependency)
                    (when library
                      (str (:catalog/group library) ":" (:catalog/name library))))]
    (when include
      {:dest.dependency/id (str project-id ":" project-path ":dependency:" index)
       :dest.dependency/kind :dest.dependency.kind/package
       :dest.dependency/include include
       :dest.dependency/version (:catalog/version library)
       :dest.dependency/source-configuration (:dependency/configuration dependency)
       :dest.dependency/source-expression (:dependency/expression dependency)})))

(defn- project-reference [project-id project-path index dependency project-id-by-path]
  (when-let [target-id (get project-id-by-path (:dependency/project dependency))]
    {:dest.dependency/id (str project-id ":" project-path ":dependency:" index)
     :dest.dependency/kind :dest.dependency.kind/project-reference
     :dest.dependency/include target-id
     :dest.dependency/path (str "../" target-id "/" target-id ".csproj")
     :dest.dependency/target-project [:dest.project/id target-id]
     :dest.dependency/source-configuration (:dependency/configuration dependency)
     :dest.dependency/source-expression (:dependency/expression dependency)}))

(defn research-mapping
  "Build a destination mapping report from a research classpath manifest."
  [classpath-report opts]
  (let [project-id (:project/id classpath-report)
        target-root (slash-path (normalize-path (or (:destination/root opts)
                                                    (:target/csharp opts)
                                                    (:csharp/root opts)
                                                    "target/research-pkl/csharp")))
        project-id-by-path (->> (:projects classpath-report)
                                (map (fn [{:project/keys [path]}]
                                       [path (csharp-project-name project-id path)]))
                                (into {}))
        catalog (catalog-by-alias classpath-report)
        projects (mapv
                  (fn [{:project/keys [path name dir] :as project}]
                    (let [dest-id (get project-id-by-path path)
                          dependencies (->> (:dependencies project)
                                            (map-indexed
                                             (fn [index dependency]
                                               (or (project-reference project-id path index dependency project-id-by-path)
                                                   (package-reference project-id path index dependency catalog))))
                                            (keep identity)
                                            vec)
                          resources (->> (:source/roots project)
                                         (filter #(= :source.kind/resources (:source/kind %)))
                                         (map-indexed (fn [index resource-root]
                                                        {:dest.item/id (str dest-id ":resource:" index)
                                                         :dest.item/kind :dest.item.kind/resource
                                                         :dest.item/path (:source/relative-path resource-root)}))
                                         vec)]
                      {:dest.project/id dest-id
                       :dest.project/source-module path
                       :dest.project/name (csharp-project-name project-id path)
                       :dest.project/source-dir dir
                       :dest.project/path (str target-root "/" dest-id "/" dest-id ".csproj")
                       :dest.project/sdk default-sdk
                       :dest.project/output-type default-output-type
                       :dest.project/target-framework default-target-framework
                       :dest.project/implicit-usings default-implicit-usings
                       :dest.project/nullable default-nullable
                       :dest.project/default-compile-items? false
                       :dest.project/items resources
                       :dest.project/dependencies dependencies}))
                  (:projects classpath-report))
        all-dependencies (mapcat :dest.project/dependencies projects)
        all-items (mapcat :dest.project/items projects)]
    {:report/type :vibeformer.report/destination-mapping
     :project/id project-id
     :target/root target-root
     :projects projects
     :projects/count (count projects)
     :project-references/count (count (filter #(= :dest.dependency.kind/project-reference
                                                  (:dest.dependency/kind %))
                                              all-dependencies))
     :packages/count (count (filter #(= :dest.dependency.kind/package
                                        (:dest.dependency/kind %))
                                    all-dependencies))
     :resources/count (count (filter #(= :dest.item.kind/resource (:dest.item/kind %))
                                     all-items))
     :helpers/count 0}))

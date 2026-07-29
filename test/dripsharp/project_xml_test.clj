(ns dripsharp.project-xml-test
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.project-xml :as project-xml]))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest structured-project-xml-renders-deterministically
  (let [project
        (project-xml/element
         "Project"
         [["Sdk" "Microsoft.NET.Sdk"]]
         [(project-xml/element
           "PropertyGroup"
           [(project-xml/element
             "Description"
             [(project-xml/text "A <deterministic> & reusable project")])])
          (project-xml/element
           "ItemGroup"
           [(project-xml/element
             "PackageReference"
             [["Include" "Example.Package"] ["Version" "1.2.3"]]
             [])])])]
    (is (= (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
                "  <PropertyGroup>\n"
                "    <Description>A &lt;deterministic&gt; &amp; reusable project</Description>\n"
                "  </PropertyGroup>\n"
                "  <ItemGroup>\n"
                "    <PackageReference Include=\"Example.Package\" Version=\"1.2.3\" />\n"
                "  </ItemGroup>\n"
                "</Project>\n")
           (project-xml/render project)))))

(deftest structured-project-xml-rejects-ambiguous-content
  (let [duplicate
        (caught
         #(project-xml/element
           "Compile"
           [["Include" "one.cs"] ["Include" "two.cs"]]
           []))
        mixed
        (caught
         #(project-xml/render
           (project-xml/element
            "Project"
            [(project-xml/text "text")
             (project-xml/element "PropertyGroup")])))]
    (is (= :duplicate-project-xml-attributes
           (:kind (ex-data duplicate))))
    (is (= :unsupported-project-xml-mixed-content
           (:kind (ex-data mixed))))))

(deftest structured-project-xml-rejects-invalid-xml-characters
  (let [invalid-text
        (caught #(project-xml/text (str "Publisher" (char 1) "Other")))
        invalid-attribute
        (caught
         #(project-xml/element
           "PackageReference"
           [["Include" (str "Package" (char 0xFFFE))]]
           []))]
    (is (= :invalid-project-xml-text
           (:kind (ex-data invalid-text))))
    (is (= :invalid-project-xml-attribute-value
           (:kind (ex-data invalid-attribute))))
    (is (project-xml/valid-text? "Publisher\t\uD83D\uDE80"))
    (is (not (project-xml/valid-text?
              (String. (char-array [(char 0xD800)])))))))

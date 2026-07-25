(ns dripsharp.java-compat-accessibility-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process])
  (:import [java.nio.file CopyOption Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn- write-string!
  [^Path file value]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file value (make-array OpenOption 0))
  file)

(defn- java-compat-assets
  []
  (let [directory (paths/resolve-path (paths/workspace-root) "runtime")]
    (->> (.listFiles (.toFile directory))
         (filter #(.isFile %))
         (filter #(str/starts-with? (.getName %) "DripSharp."))
         (sort-by #(.getName %))
         vec)))

(defn- installed-target-framework
  []
  (let [runtimes (:output (process/run! {:directory (paths/workspace-root)
                                         :command ["dotnet" "--list-runtimes"]}))
        majors (keep (fn [line]
                       (when-let [[_ major]
                                  (re-find #"^Microsoft\.NETCore\.App (\d+)\." line)]
                         (parse-long major)))
                     (str/split-lines runtimes))]
    (str "net" (apply max majors) ".0")))

(def ^:private signature-probe
  (str
   "#nullable enable\n"
   "using System;\n"
   "using System.Reflection;\n\n"
   "namespace DripSharp.Runtime;\n\n"
   "internal static class Program\n"
   "{\n"
   "    private static void Assert(bool condition, string message)\n"
   "    {\n"
   "        if (!condition) throw new Exception(message);\n"
   "    }\n\n"
   "    private static MethodInfo Method(Type owner, string name) =>\n"
   "        owner.GetMethod(name) ?? throw new Exception($\"Missing {owner.Name}.{name}\");\n\n"
   "    public static void Main()\n"
   "    {\n"
   "        var decoder = Method(typeof(JavaCharsetDecoder), nameof(JavaCharsetDecoder.Decode));\n"
   "        Assert(decoder.GetParameters() is [{ ParameterType: var decoderParameter }] &&\n"
   "               decoderParameter == typeof(JavaByteBuffer),\n"
   "               \"JavaCharsetDecoder.Decode must accept JavaByteBuffer\");\n\n"
   "        var aliases = Method(typeof(JavaKeyStore), nameof(JavaKeyStore.Aliases));\n"
   "        Assert(aliases.ReturnType == typeof(JavaIterator<string>),\n"
   "               \"JavaKeyStore.Aliases must return JavaIterator<string>\");\n\n"
   "        var iterableIterator = Method(typeof(JavaIterableContract<>), \"Iterator\");\n"
   "        Assert(iterableIterator.ReturnType.IsGenericType &&\n"
   "               iterableIterator.ReturnType.GetGenericTypeDefinition() == typeof(JavaIterator<>),\n"
   "               \"JavaIterableContract.Iterator must return JavaIterator<T>\");\n\n"
   "        var listIterator = Method(typeof(JavaListContract<>), \"Iterator\");\n"
   "        Assert(listIterator.ReturnType.IsGenericType &&\n"
   "               listIterator.ReturnType.GetGenericTypeDefinition() == typeof(JavaIterator<>),\n"
   "               \"JavaListContract.Iterator must return JavaIterator<T>\");\n\n"
   "        Assert(new JavaSoftReference<string>(null).Get() is null,\n"
   "               \"Java SoftReference must accept and retain a null referent\");\n"
   "        Assert(new JavaWeakReference<string>(null).Get() is null,\n"
   "               \"Java WeakReference must accept and retain a null referent\");\n\n"
   "        foreach (var type in new[]\n"
   "                 {\n"
   "                     typeof(JavaByteBuffer), typeof(JavaCharsetDecoder),\n"
   "                     typeof(JavaIterator<>), typeof(JavaKeyStore),\n"
   "                     typeof(JavaIterableContract<>), typeof(JavaListContract<>)\n"
   "                 })\n"
   "            Assert(!type.IsPublic, $\"{type.Name} widened the Pkl product surface\");\n\n"
   "        Console.Write(\"OK\");\n"
   "    }\n"
   "}\n"))

(deftest pkl-java-compat-signatures-align-with-internal-carrier-types
  (let [root (Files/createTempDirectory "dripsharp-java-compat-accessibility"
                                        (make-array FileAttribute 0))
        source-root (paths/resolve-path root "src")
        target-framework (installed-target-framework)
        project
        (write-string!
         (paths/resolve-path root "JavaCompatAccessibility.csproj")
         (str
          "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
          "  <PropertyGroup>\n"
          "    <OutputType>Exe</OutputType>\n"
          "    <TargetFramework>" target-framework "</TargetFramework>\n"
          "    <Nullable>enable</Nullable>\n"
          "    <ImplicitUsings>disable</ImplicitUsings>\n"
          "    <DefineConstants>DRIPSHARP_INTERNAL_JAVA_COMPAT</DefineConstants>\n"
          "    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>\n"
          "  </PropertyGroup>\n"
          "  <ItemGroup><Compile Include=\"src/**/*.cs\" /></ItemGroup>\n"
          "</Project>\n"))]
    (doseq [asset (java-compat-assets)]
      (let [destination (paths/resolve-path source-root (.getName asset))]
        (Files/createDirectories (.getParent destination)
                                 (make-array FileAttribute 0))
        (Files/copy (.toPath asset)
                    destination
                    (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    (write-string! (paths/resolve-path source-root "Program.cs") signature-probe)
    (let [build (process/run! {:directory root
                               :command ["dotnet" "build" project "--nologo"
                                         "--configuration" "Release"
                                         "--verbosity:quiet"]})
          run (process/run! {:directory root
                             :command ["dotnet" "run" "--project" project
                                       "--configuration" "Release" "--no-build"]})]
      (is (zero? (:exit build)) (:output build))
      (is (zero? (:exit run)) (:output run))
      (is (= "OK" (str/trim (:output run)))))))

(ns dripsharp.nuget-framework
  "NuGet-specific framework identifiers derived from project and asset TFMs.")

(defn canonical-dependency-framework
  "Derives the exact NuGet nuspec dependency-group identifier without changing
  the project or asset-path TFM that supplied it."
  [target-framework]
  (if-let [[_ version] (re-matches #"netstandard(\d+\.\d+)" target-framework)]
    (str ".NETStandard" version)
    target-framework))

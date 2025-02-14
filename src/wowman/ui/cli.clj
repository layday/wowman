(ns wowman.ui.cli
  (:require
   [taoensso.timbre :as timbre :refer [spy info]]
   [wowman
    [catalog :as catalog]
    [http :as http]
    [utils :as utils]
    [curseforge-api :as curseforge-api]
    [wowinterface :as wowinterface]
    [core :as core :refer [get-state paths find-catalog-local-path]]]))

(defmulti action
  "handles the following actions:
    :scrape-wowinterface-catalog - scrapes wowinterface host and creates a wowinterface catalog
    :scrape-curseforge-catalog - scrapes curseforge host and creates a curseforge catalog
    :scrape-catalog - scrapes all available sources and creates a full and short catalog
    :list - lists all installed addons
    :list-updates - lists all installed addons with updates available
    :update-all - updates all installed addons with updates available"
  (fn [x]
    (cond
      (map? x) (:action x)
      (keyword? x) x)))

(defmethod action :scrape-wowinterface-catalog
  [_]
  (binding [http/*cache* (core/cache)]
    (wowinterface/scrape (find-catalog-local-path :wowinterface))))

(defmethod action :scrape-curseforge-catalog
  [_]
  (binding [http/*cache* (core/cache)]
    (let [output-file (find-catalog-local-path :curseforge)
          catalog-data (curseforge-api/download-all-summaries-alphabetically)
          created (utils/datestamp-now-ymd)
          updated created
          formatted-catalog-data (catalog/format-catalog-data catalog-data created updated)]
      (catalog/write-catalog formatted-catalog-data output-file))))

(defmethod action :write-catalog
  [_]
  (let [curseforge-catalog (find-catalog-local-path :curseforge)
        wowinterface-catalog (find-catalog-local-path :wowinterface)
        catalog (catalog/merge-catalogs curseforge-catalog wowinterface-catalog)]
    (-> catalog
        (catalog/write-catalog (find-catalog-local-path :full))

        catalog/shorten-catalog
        (catalog/write-catalog (find-catalog-local-path :short)))))

(defmethod action :scrape-catalog
  [_]
  (action :scrape-curseforge-catalog)
  (action :scrape-wowinterface-catalog)
  (action :write-catalog))

(defmethod action :list
  [_]
  (let [installed-addons (get-state :installed-addon-list)]
    (println (count installed-addons) "installed addons")
    (doseq [{:keys [dirname installed-version]} installed-addons]
      (println (format "%s (%s)" dirname, installed-version)))))

(defmethod action :list-updates
  [_]
  (let [installed-addons (get-state :installed-addon-list)
        updates (filter :update? installed-addons)]
    (println (count installed-addons) "installed")
    (println (count updates) "updates")
    (doseq [{:keys [dirname installed-version version]} updates]
      (println (format "%s (%s => %s)" dirname, installed-version version)))))

(defmethod action :update-all
  [_]
  (core/install-update-all)
  (action :list-updates))

(defmethod action :default
  [opts]
  (println opts))

(defn start
  [opts]
  (info "starting cli")
  (core/refresh)
  (action opts))

(defn stop
  []
  (info "stopping cli")
  nil)

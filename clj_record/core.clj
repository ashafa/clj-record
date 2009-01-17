(ns clj-record.core
  (:require [clojure.contrib.sql :as sql])
  (:use clj-record.util)
  (:use clj-record.config)
  (:use clojure.contrib.str-utils)
  (:use clojure.contrib.test-is))


(defn table-name [model-name]
  (pluralize (if (string? model-name) model-name (name model-name))))

(defn find-record [model-name id]
  (sql/with-connection db
    (sql/with-query-results rows [(format "select * from %s where id = ?" (table-name model-name)) id]
      (merge {} (first rows)))))

(defn- to-condition [[attribute value]] ; XXX: should be using PreparedStatement for smarter quoting
  (str
    (name attribute)
    (if (nil? value)
      " IS NULL"
      (str " = " (if (string? value) (format "'%s'" value) value)))))

(defn to-conditions [attributes]
  (str-join " AND " (map to-condition attributes)))

(defn find-records [model-name attributes]
  (let [select-query (format "select * from %s where %s" (table-name model-name) (to-conditions attributes))]
    (sql/with-connection db
      (sql/with-query-results rows [select-query]
        (doall (map #(merge {} %) rows))))))

(defn create [model-name attributes]
  (sql/with-connection db
    (let
      [key-vector (keys attributes)
       val-vector (map attributes key-vector)
       id (sql/transaction
            (sql/insert-values (table-name model-name) key-vector val-vector)
            (sql/with-query-results rows ["VALUES IDENTITY_VAL_LOCAL()"] (:1 (first rows))))] ; XXX: db-vendor-specific
      (find-record model-name id))))

(defn destroy-record [model-name record]
  (sql/with-connection db
    (sql/do-prepared
      (format "delete from %s where id = ?" (table-name model-name))
      [(:id record)])))

(defn destroy-records [model-name attributes]
  (sql/with-connection db
    (sql/do-prepared
      (format "delete from %s where %s" (table-name model-name) (to-conditions attributes)) [])))

(defn- defs-from-option-groups [model-name option-groups]
  (reduce
    (fn [def-forms [option-group-name & options]]
      (let [option-ns (symbol (str "clj-record." (name option-group-name)))
            fn-sym 'handle-option
            handle-option-fn (ns-resolve option-ns fn-sym)]
        (if (nil? handle-option-fn) (throw (RuntimeException. (format "%s/%s not defined" option-ns fn-sym))))
        (reduce
          (fn [def-forms option-form]
            (let [new-defs (apply handle-option-fn model-name option-form)]
              (if new-defs (conj def-forms new-defs) def-forms)))
          def-forms
          options)))
    []
    option-groups))

(def all-models-metadata (ref {}))

(defn- setup-model-metadata [model-name]
  (dosync (commute all-models-metadata assoc model-name (ref {}))))

(defmacro init-model [& options]
  (let [model-name (last (re-split #"\." (name (ns-name *ns*))))]
    (setup-model-metadata model-name)
    (let [optional-forms (defs-from-option-groups model-name options)]
    `(do
      (defn ~'table-name [] (table-name ~model-name))
      (defn ~'find-record [id#]
        (find-record ~model-name id#))
      (defn ~'find-records [attributes#]
        (find-records ~model-name attributes#))
      (defn ~'create [attributes#]
        (create ~model-name attributes#))
      (defn ~'destroy-record [record#]
        (destroy-record ~model-name record#))
      (defn ~'validate [record#]
        (clj-record.validation/validate ~model-name record#))
      ~@optional-forms))))

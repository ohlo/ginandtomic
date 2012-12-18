(ns datibernate.query
  ( :require [clojure.reflect :as reflect]
             [datomic.api :only [q db] :as d]
             [clojure.string :as str]
             [datibernate.reflect :as my-reflect]
             [datibernate.gen-from-db :as gen]))

(comment
(use '[datomic.api :only [q db] :as d])
(use 'clojure.pprint)

;(def uri "datomic:mem://seattle")
                                        
(def uri "datomic:mem://billshare")
(d/create-database uri)
(def conn (d/connect uri))
(def schema-tx (read-string (slurp "../billshare/resources/schema/bill-schema.clj")))
@(d/transact conn schema-tx)
(def data-tx (read-string (slurp "../billshare/resources/schema/billshare-sampledata.clj")))
;; submit schema transaction
@(d/transact conn data-tx)

(->> (d/q '[:find ?c :where [?c :community/name ?x]] (d/db conn)) ffirst (d/entity (d/db conn)))
)

(defn adapt-method [class method]
  (let [method-name  (:name method)
        method-returns  (:return-type method)
        declaring-class (:declaring-class method)
        return-type-val (:return-type-val method)
        attribute-val (:attribute-val method)
        is-enum (and return-type-val (= java.lang.Enum (.getSuperclass return-type-val)))]
    ;; todo use assignableFrom
    [method-name (keyword attribute-val) return-type-val is-enum]))

(defmacro reify-from-maps [classname datal emit-map reify-class-fn & ms]
  `(reify ~classname ~@(apply concat
             (for [[method-name datomic-key return-type is-enum] ms]
               (if is-enum
                 ((:enum emit-map) datal method-name datomic-key return-type)
                 (if return-type
                   ((:datomic-entity emit-map) datal method-name datomic-key return-type reify-class-fn)
                   ((:straight-get emit-map) datal method-name datomic-key)))))))

(def emit-method-body
  {:straight-get (fn [data method-name datomic-key]
                   [`(~method-name [~'this] (~datomic-key ~data))])
   :enum (fn [data method-name datomic-key return-type]
           [`(~method-name [~'this]  (. ~return-type ~'valueOf (name (~datomic-key ~data))))])
   :datomic-entity (fn [data method-name datomic-key return-type reify-fn]                     
                     [ `(~method-name [~'this]                                      
                                      (if (.isAssignableFrom java.util.Collection (.getClass (~datomic-key ~data)))
                                        (map (~reify-fn ~return-type) (~datomic-key ~data))
                                        ((~reify-fn ~return-type) (~datomic-key ~data))))])})

(defn get-methods-from-class [class]
  (comment [['getName :name String false] ['getActiveStatus :status generated.User$ActiveStatus true]])
  (let [methods (:members (my-reflect/reflect-methods class))]
    (map (partial adapt-method class) methods)))

(comment (memoize)) ;;memo had probs... not sure why

(def create-dict-getter-adapta
  (fn [class]
    (let [asym (gensym)
          ms (get-methods-from-class class)
          classname (symbol (.getName class))]       
      (eval     
       `(fn [~asym] (reify-from-maps ~classname ~asym ~emit-method-body ~create-dict-getter-adapta ~@ms))))))

(comment (.getRelationshipStatus (first (.getUserGroupRelation ((create-dict-getter-adapta generated.User) {:user/userGroupRelation [{:userGroupRelation/relationshipStatus "ACTIVE"}]})))))

(comment 
  (def tst
    (fn [class]
      (let [asym (gensym)
            ms (get-methods-from-class class)
            classname (symbol (.getName class))] 
        (fn [asym]             
          (macroexpand-1 `(reify-from-maps ~classname ~asym ~emit-method-body ~create-dict-getter-adapta ~@ms)))))))

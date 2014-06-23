(ns puget.printer
  "Functions for canonical colored printing of EDN values."
  (:require
    [clojure.string :as str]
    [fipp.printer :as fipp]
    (puget
      [ansi :as ansi]
      [data :as data]
      [order :as order])))


;; CONTROL VARS

(def ^:dynamic *strict-mode*
  "If set, throw an exception if there is no defined canonical print method for
  a given value."
  false)


(def ^:dynamic *map-delimiter*
  "The text placed between key-value pairs."
  "")


(def ^:dynamic *colored-output*
  "Output ANSI colored output from print functions."
  false)


(def ^:dynamic *color-scheme*
  "Maps various syntax elements to color codes."
  {; syntax elements
   :delimiter [:bold :red]
   :tag       [:red]

   ; primitive values
   :nil       [:bold :black]
   :boolean   [:green]
   :number    [:cyan]
   :string    [:bold :magenta]
   :keyword   [:bold :yellow]
   :symbol    nil

   ; special types
   :function-symbol [:bold :blue]
   :class-delimiter [:blue]
   :class-name      [:bold :blue]})


(defmacro with-color
  "Executes the given expressions with colored output enabled."
  [& body]
  `(binding [*colored-output* true]
     ~@body))


(defn set-color-scheme!
  "Sets the color scheme for syntax elements. Pass either a map to merge into
  the current color scheme, or a single element/colors pair. Colors should be
  vector of color keywords."
  ([colors]
   (alter-var-root #'*color-scheme* merge colors))
  ([element colors & more]
   (set-color-scheme! (apply hash-map element colors more))))


(defn set-map-commas!
  "Alters the *map-delimiter* var to be a comma."
  []
  (alter-var-root #'*map-delimiter* (constantly ",")))



;; COLORING FUNCTIONS

(defn- color-doc
  "Constructs a text doc, which may be colored if *colored-output* is true.
  Element must be a key from the color-scheme map."
  [element text]
  (let [codes (seq (*color-scheme* element))]
    (if (and *colored-output* codes)
      [:span [:pass (ansi/esc codes)] text [:pass (ansi/escape :none)]]
      text)))


(defn color-text
  "Produces text colored according to the active color scheme. This is mostly
  useful to clients which want to produce output which matches data printed by
  Puget, but which is not directly printed by the library. Note that this
  function still obeys the *colored-output* var."
  [element text]
  (let [codes (seq (*color-scheme* element))]
    (if (and *colored-output* codes)
      (str (ansi/esc codes) text (ansi/escape :none))
      text)))



;;;;; CANONIZING MULTIMETHOD ;;;;;

(defn- canonize-dispatch
  [value]
  (if (satisfies? data/TaggedValue value)
    :tagged-value
    (class value)))


(defmulti canonize
  "Converts the given value into a 'canonical' structured document, suitable
  for printing with fipp. This method also supports ANSI color escapes for
  syntax highlighting if desired."
  #'canonize-dispatch)



;;;;; PRIMITIVE VALUES ;;;;;

(defmacro ^:private canonize-element
  "Defines a canonization of a primitive value type by mapping it to an element
  in the color scheme."
  [dispatch element]
  `(defmethod canonize ~dispatch
     [value#]
     (color-doc ~element (pr-str value#))))


(canonize-element nil                  :nil)
(canonize-element java.lang.Boolean    :boolean)
(canonize-element java.lang.Number     :number)
(canonize-element java.lang.Character  :string)
(canonize-element java.lang.String     :string)
(canonize-element clojure.lang.Keyword :keyword)
(canonize-element clojure.lang.Symbol  :symbol)



;;;;; COLLECTIONS ;;;;;

(defmethod canonize clojure.lang.ISeq
  [s]
  (let [elements (if (symbol? (first s))
                   (cons (color-doc :function-symbol (str (first s)))
                         (map canonize (rest s)))
                   (map canonize s))]
    [:group
     (color-doc :delimiter "(")
     [:align (interpose :line elements)]
     (color-doc :delimiter ")")]))


(defmethod canonize clojure.lang.IPersistentVector
  [v]
  [:group
   (color-doc :delimiter "[")
   [:align (interpose :line (map canonize v))]
   (color-doc :delimiter "]")])


(defmethod canonize clojure.lang.IPersistentSet
  [s]
  (let [entries (sort order/rank (seq s))]
    [:group
     (color-doc :delimiter "#{")
     [:align (interpose :line (map canonize entries))]
     (color-doc :delimiter "}")]))


(defn- canonize-map
  [m]
  (let [canonize-kv
        (fn [[k v]]
          [:span
           (canonize k)
           (cond
             (satisfies? data/TaggedValue v) " "
             (coll? v) :line
             :else " ")
           (canonize v)])
        entries (->> (seq m)
                     (sort-by first order/rank)
                     (map canonize-kv))]
    [:group
     (color-doc :delimiter "{")
     [:align (interpose [:span *map-delimiter* :line] entries)]
     (color-doc :delimiter "}")]))


(defmethod canonize clojure.lang.IPersistentMap
  [m]
  (canonize-map m))


(defmethod canonize clojure.lang.IRecord
  [r]
  (if *strict-mode*
    (throw (IllegalArgumentException.
             (str "No canonical representation for " (class r) ": " r)))
    [:span
     (color-doc :delimiter "#")
     (.getName (class r))
     (canonize-map r)]))


(prefer-method canonize clojure.lang.IRecord clojure.lang.IPersistentMap)



;;;;; CLOJURE-SPECIFIC TYPES ;;;;;

; TODO: regex


(defmethod canonize clojure.lang.Var
  [v]
  (when *strict-mode*
    (throw (IllegalArgumentException.
             (str "No canonical representation for " (class v) ": " v))))
  [:span
   (color-doc :delimiter "#'")
   (color-doc :symbol (subs (str v) 2))])



;;;;; OTHER TYPES ;;;;;

(defmethod canonize :tagged-value
  [tv]
  (let [tag   (data/edn-tag tv)
        value (data/edn-value tv)]
    [:span
     (color-doc :tag (str \# tag))
     (if (coll? value) :line " ")
     (canonize value)]))


(defmethod canonize :default
  [value]
  (if *strict-mode*
    (throw (IllegalArgumentException.
             (str "No canonical representation for " (class value) ": " value)))
    [:span
     (color-doc :class-delimiter "#<")
     (color-doc :class-name (.getName (class value)))
     " " (str value)
     (color-doc :class-delimiter ">")]))



;;;;; PRINT FUNCTIONS ;;;;;

(def ^:private default-opts
  "Default Puget printing options."
  {:width 80})


(defn pprint
  ([value]
   (pprint value default-opts))
  ([value opts]
   (fipp/pprint-document (canonize value) opts)))


(defn pprint-str
  "Pretty-print a value to a string."
  ([value]
   (pprint-str value default-opts))
  ([value opts]
   (-> value
       (pprint opts)
       with-out-str
       str/trim-newline)))


(defn cprint
  "Like pprint, but turns on colored output."
  ([value]
   (cprint value default-opts))
  ([value opts]
   (binding [*colored-output* true]
     (pprint value opts))))


(defn cprint-str
  "Pretty-prints a value to a colored string."
  ([value]
   (cprint-str value default-opts))
  ([value opts]
   (-> value
       (cprint opts)
       with-out-str
       str/trim-newline)))

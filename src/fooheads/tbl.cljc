(ns fooheads.tbl
  "A table is vector of vectors where the first row represents the header
  and the rest represent the rows"
  (:refer-clojure :exclude [cat])
  (:require
    [clojure.string :as str]
    [fooheads.stdlib :refer [cljs-env?
                             conjt
                             index-of
                             named?
                             partition-indexes
                             partition-using
                             qualify-ident
                             removet
                             singleton? transpose]])
  #?(:cljs
     (:require-macros
       [fooheads.tbl])))


(def nil-value? (comp nil? val))
(def divider-line '---)


(defn- divider-line?
  [x]
  (= x divider-line))


(defn- blank-line?
  [row]
  (every? nil? row))


(defn- coerce
  [col-header data coercions]
  (reduce
    (fn [data [k index]]
      (if-let [f (get coercions k)]
        (mapv #(update (vec %) index f) data)
        data))
    data
    (zipmap col-header (range))))


(defn- token
  [token]
  (if (symbol? token)
    `(quote ~token)
    token))


(defn- resolve-symbol
  [env _sym]
  (if (cljs-env? env)
    #?(:cljs nil
       :clj (throw (ex-info "resolve is not supported in cljs" {})))
    #?(:cljs nil
       :clj (resolve _sym))))


(defn -token-or-resolve
  [env token]
  (if (symbol? token)
    (or (some->> token (resolve-symbol env))  `(quote ~token))
    token))


(defmacro tokenize
  "Tokenizes the elements sent in. If the first or last element is a map,
  that map is considered being a map of options.

  `tokenize` itself is only interested in one option: resolve. If the resolve
  option is true, any symbol is trying to be resolved. If the symbol can't
  be resolved, it stays a symbol. This is necessary since the dividers in the
  table are built up with symbols.

  The function returns a map with :tokens, containing all tokens (with resolved
  or unresolved symbols) and :opts for all options in the options map,
  except :resolve (since that is consumed by tokenize).

  The reason for having `tokenize` resolving instead of (for instance) `interpret`
  is that `tokenize` is a macro, which means that is is in the right context to
  resolve whatever the user code is refering to, while a normal function is not."
  [opts & elements]
  (let [should-resolve (:resolve opts)]
    (if should-resolve
      (mapv (partial -token-or-resolve &env) elements)
      (mapv token elements))))


(defmulti table-format (fn [fmt _col-headers _row-headers _data] fmt))


(defmethod table-format :map [_ col-headers _row-headers data]
  {:col-headers col-headers :data data})


(defmethod table-format :maps [_ col-headers _row-headers data]
  (mapv #(zipmap col-headers %) data))


(defmethod table-format :table [_ col-headers _row-headers data]
  (vec (cons col-headers data)))


(defmethod table-format :relation [_ col-headers row-headers data]
  (set (table-format :maps col-headers row-headers data)))


(defmethod table-format :cells [_ col-headers row-headers data]
  (mapcat
    (fn [[row-header data-row]]
      (map
        (fn [[col-header value]]
          {:col-header col-header :row-header row-header :value value})
        (map vector col-headers data-row)))

    (map vector row-headers data)))


(defmethod table-format :default [_ col-headers row-headers data]
  (merge
    (when (seq col-headers) {:col-headers col-headers})
    (when (seq row-headers) {:row-headers row-headers})
    {:data data}))


(defn- extract-cells
  [separator-f row]
  (->>
    row
    (partition-using #(separator-f (last %)))
    (rest)
    (mapv (comp vec butlast))
    (mapv #(cond
             (= 0 (count %)) nil
             (= 1 (count %)) (first %)
             :else %))))


(defn tabularize
  "Interprets tokens into a 'table', a vector of vectors, using either the option
  :width to determine the number of columns per row, or if :width is not present,
  figures out the width using :divider to find a divider row.

  In order to interpret the table dsl, opts can be provided with a :separator
  and :divider. :separator is the token between columns and :divider is the
  column value in the divider row.

  Default opts are: `{:separator '| :divider #\"-{3,}\"}`

  Takes as arguments either a seq of tokens and an opts map, or a map
  with :tokens and :opts (which is the output of `tokenize`)."

  ([tokens]
   (tabularize {} tokens))

  ([opts tokens]
   (let [default-opts  {:separator '| :divider #"-{3,}"}
         {:keys [divider separator]} (merge default-opts opts)

         separator?        #(= separator %)
         divider?          #(and divider (named? %) (re-find divider (name %)))
         divider-line?     #(every? divider? %)
         make-divider-line #(if (divider-line? %) divider-line %)
         calculated-width  (->> tokens (map #(if (divider? %) 1 0)) (apply +))
         width             (or (:width opts) calculated-width)
         dividers-per-row  (inc width)

         num-eq (fn [n v] (fn [coll] (= n (count (filter #(= % v) coll)))))
         make-rows #(partition-using (num-eq dividers-per-row separator) %)

         data
         (->>
           tokens
           (make-rows)
           (map (partial extract-cells separator?))
           (mapv make-divider-line))]


     data)))


(defn interpret
  "Interprets the tabular data into a map with :col-headers, :row-headers, :data
  and :transformations.

  :col-headers     - a seq of column headers
  :row-headers     - a seq of row headers
  :data            - a vector of vectors with row values
  :transformations - a map with specificed tranformations to be done.

  The interpretation can be configured using
  :

  ;;;;In order to interpret the table dsl, options can be
  ;;;;provided with a :separator and :divider. :seperator is the token
  ;;;;between columns and :divider is the column value in the divider
  ;;;;row. Default interpretation-opts are:
  ;;;;`{:separator '| :divider #\"-{2,}\"}

  "

  ([table]
   (interpret {} table))

  ([opts table]
   (let [default-opts {:col-header-idxs :auto
                       :row-header-idxs :auto
                       :coercion-idx nil}

         opts (merge default-opts opts)

         col-header-idxs (or (:col-header-idxs opts) [])
         row-header-idxs (or (:row-header-idxs opts) [])
         coercion-idx (:coercion-idx opts)

         coercion-row? (fn [row] (some var? row))

         col-header-idxs (if (= :auto col-header-idxs)
                           (range (or (index-of divider-line? table) 1))
                           col-header-idxs)

         row-header-idxs (if (= :auto row-header-idxs)
                           (let [row (first table)]
                             (if (divider-line? row)
                               []
                               (range (or (count (take-while nil? (first table))) 0))))
                           row-header-idxs)

         find-coercion-idx
         (fn [table]
           (reduce
             (fn [acc [a-row b-row index]]
               (if (and (divider-line? b-row) (coercion-row? a-row))
                 (reduced #{index})
                 acc))
             #{}
             (map vector table (rest table) (range))))

         coercion-idxs
         (if coercion-idx
           [coercion-idx]
           (find-coercion-idx table))

         ;; now that we have used the divider line, remove it
         ;; assumes that all col-headers comes before the divider-line
         table (remove divider-line? table)

         [col-headers coercions data]
         (partition-indexes [col-header-idxs coercion-idxs] table)


         [row-headers data]
         (->>
           (transpose data)
           (partition-indexes [row-header-idxs])
           (mapv transpose))

         col-headers (transpose col-headers)

         col-headers
         (if (every? singleton? col-headers)
           (mapv first col-headers)
           col-headers)

         row-headers
         (if (every? singleton? row-headers)
           (mapv first row-headers)
           row-headers)


         coercions
         (some->> coercions
                  (first)
                  (zipmap col-headers)
                  (removet nil-value?))

         ;; remove the empty top corner
         col-headers (vec (drop (count row-header-idxs) col-headers))]

     (merge
       (when (seq col-headers) {:col-headers col-headers})
       (when (seq row-headers) {:row-headers row-headers})
       (when (seq coercions) {:coercions coercions})
       {:data (vec data)}))))


(defn col-headers
  [interpretation]
  (:col-headers interpretation))


(defn row-headers
  [interpretation]
  (:row-headers interpretation))


(defn data
  [interpretation]
  (:data interpretation))


(defn transformations
  [interpretation]
  (:transformations interpretation))


(defn transform
  "Transforms an interpretation. Example:

  {:col-header [:date :value :value2]
  :data [[\"2021-07-01\" 10 100] [\"2021-07-02\" 20 200]]
  :opts {:coercions {:value double :date t/date :value2 str)}
  :ns :foo}}

  opts is a map of options. Valid options are (applied in this order):

  :remove-blank-lines?  If true, entries with only blank values are removed.

  :coercions            A map of keys and functions, where the key represents
  the attr/column and the function is a function to apply
  to the values of that key.

  :renames              A map or renames in the same form as
  `clojure.set/rename-keys`

  :ns                   A keyword that will be the namespace for all keys.
  Note, that renames are applied before ns.

  :format               :map - a map with {:col-header header :data data}

  :maps - vector of maps with header as keys

  :relation - a relation in clojure.set style

  :table - a csv-like table with header as first row

  opts example:

  {:remove-blank-lines? true}
  :coercions           {:date t/date}
  :renames             {:date :from-date}
  :ns                  {:some-ns}
  :format              :maps}

  "

  [transformations interpretation]
  (let [{:keys [col-headers row-headers data]} interpretation
        data (if (:remove-blank-lines? transformations)
               (remove blank-line? data)
               data)

        data (coerce col-headers data (:coercions transformations))

        col-headers (replace (:renames transformations) col-headers)

        col-headers (if-let [nspace (:ns transformations)]
                      (mapv (partial qualify-ident nspace) col-headers)
                      col-headers)]

    (table-format (:format transformations) col-headers row-headers data)))


(defn -extract-opts
  "Extracts the opts from the tokens and returns [opts tokens]"
  [tokens]
  (cond
    (map? (first tokens))
    [(first tokens) (rest tokens)]

    (map? (last tokens))
    [(last tokens) (butlast tokens)]

    :else
    [{} tokens]))


(defmacro tbl
  "Macro for common tables"
  [& elements]
  `(let [elements# (tokenize {:resolve false} ~@elements)
         [table-opts# tokens#] (-extract-opts elements#)

         default-opts# {:format :maps}
         opts# (merge default-opts# table-opts#)]

     (->>
       tokens#
       (tabularize opts#)
       (interpret opts#)
       (transform opts#))))


(defn- repeat-row?
  [row]
  (= '* (first row)))


(defn apply-template
  "Apply a template to (:table) data with the same shape and extract the data
  matching the keywords in the template. A template can represent a map and a
  collection of maps in one template.

  A template that looks like this:

  `[()
  [Customer :order/customer]
  [Date :order/date]
  [nil nil]
  [Article Quantity]
  [* *]
  [:order-line/article-id :order-line/quantity]]`

  appled to this data:

  `[()
  [Customer \"John Doe\" nil]
  [Date \"2024-08-25\" nil]
  [nil nil nil]
  [Article Desc Quantity]
  [103 \"Bread\" 1]
  [234 \"Milk 1L\" 4
  [666 \"BBQ Sauce\" 1]]

  will result in a this data structure:

  `[{}
  {:order/customer \"John Doe\"}
  {:order/date \"2024-08-25\"}
  {}
  {}
  [{:order-line/article-id 103 :order-line/quantity \"Bread\"}]
  [{:order-line/article-id 234 :order-line/quantity \"Milk 1L\"}]
  [{:order-line/article-id 666 :order-line/quantity \"BBQ Sauce\"}]]

  where each line in the collection part is wrapped in a vector in order
  to differentiate the top level values from the collection entries in further
  post processing into domain data.

  Note that both the data and the templates can easily be created by using
  the `tbl` macro with the option `{:format :table}`. Example:

  `(tbl
  {:format :table}
  | ---                    | ---                  |
  | Customer               | :order/customer      |
  | Date                   | :order/date          |
  |                        |                      |
  | Article                | Quantity             |
  | *                      | *                    |
  | :order-line/article-id | :order-line/quantity |)`
  "

  [template data]
  (loop
    [template-index 0
     data-index 0
     coll-mode false
     result []]

    (let [template-row (get template template-index)
          data-row (get data data-index)]

      (if (< data-index (count data))
        (cond
          (repeat-row? template-row)
          (recur (inc template-index) data-index true result)

          :else
          (let [row
                (->>
                  (map vector template-row data-row)
                  (mapcat (fn [[k v]] (when (keyword? k) [k v])))
                  (apply hash-map))]

            (recur
              (if coll-mode template-index (inc template-index))
              (inc data-index)
              coll-mode
              (conj result (if coll-mode [row] row)))))
        result))))


(defn make-comparator
  [order]
  (let [n (count order)
        order-map
        (if (vector? order)
          (->>
            order
            (map-indexed (fn [index x] [x index]))
            (into {}))
          order)]

    (fn [x y]
      (compare (get order-map x n) (get order-map y n)))))


(defn relation->tbl
  "Returns a string that represents the code that created the relation.
  Typically used as a helper function to generate data to be used in tests.

  `key-order` can be either a vector of keys in the order they should appear,
  or a map from key to position (int), or a comparator function."
  ([relation]
   (relation->tbl nil relation))
  ([key-order relation]
   (let [cmp-f (make-comparator key-order)
         header (sort cmp-f (keys (first relation)))
         separator (map (constantly '---) header)
         data (map (apply juxt header) relation)

         s
         (->>
           data
           (cons separator)
           (cons header)
           (map #(str "  | " (str/join " | " (map pr-str %)) " |"))
           (str/join "\n"))]
     (str "(tbl\n" s ")"))))


(defn- template-key
  [row]
  (mapv #(if (symbol? %) % nil) row))


(defn- match-template
  [template-map row]
  (let [template-key (template-key row)
        template-value (get template-map template-key)]

    (when (and (nil? template-value) (seq (remove nil? template-key)))
      (throw (ex-info "No matching template" {:row row})))

    template-value))


(defn- extract-data
  [template-value data-row]
  (reduce
    (fn [m [k path]]
      (merge m {k (get data-row path)}))
    {}
    (:row-template template-value)))


(defn- update-last
  [xs f & args]
  (apply update xs (dec (count xs)) f args))


(defn- update-state
  [state path data]
  (if (empty? path)
    (merge state data)
    (assoc-in state path data)))


(defn- single-row-template?
  [row]
  (and (seq (filter symbol? row))
       (seq (filter keyword? row))))


(defn- multi-row-template?
  [row]
  (and (seq (filter symbol? row))
       (not (seq (filter keyword? row)))))


(defn- row-template
  [row]
  (->>
    row
    (map-indexed
      (fn [index x]
        (if (keyword? x)
          [x index]
          nil)))
    (remove nil?)
    (into {})))


(defn- parse-template
  [template]
  (loop [state {}
         [row & rows] template]

    (cond
      (nil? row)
      state

      (blank-line? row)
      (recur state rows)

      (single-row-template? row)
      (let [template-value {:row-template (row-template row)}
            state (assoc state (template-key row) template-value)]

        (recur state rows))

      (multi-row-template? row)
      (let [[coll-attr-names attr-names & rows] rows
            attr-name  (->> coll-attr-names (filter keyword?) first)
            template-value {:attr-name attr-name
                            :row-template (row-template attr-names)}
            state (assoc state (template-key row) template-value)]

        (recur state rows))

      :else
      (throw (ex-info "Not a template row" {:row row})))))


(defn table->tree
  "Generate a tree from a template and a data table (vector of vectors)."
  [template rows]
  (let [template-map (parse-template template)]
    (loop [state {}
           [row & rows] rows
           template-values []
           path []]

      (cond
        (nil? row)
        state

        :else
        (let [template-value (match-template template-map row)]
          (cond

            (blank-line? row)
            (recur state rows
                   (vec (butlast template-values))
                   (vec (butlast (butlast path))))

            (nil? template-value)
            (let [template-value (last template-values)
                  data (extract-data template-value row)
                  [state path]
                  (if (:attr-name template-value)
                    (let [path (update-last path inc)]
                      [(update-state state path data) path])
                    [(update-state state path data) path])]
              (recur state rows template-values path))

            :else
            (if (:attr-name template-value)
              (let [template-values (conjt template-values template-value)
                    path (into path [(:attr-name template-value) -1])
                    state (assoc-in state (butlast path) [])]
                (recur state rows template-values path))
              (let [data (extract-data template-value row)
                    state (update-state state path data)]
                (recur state rows template-values path)))))))))


;; # Higher level CSV parsing functionality
;;
;; The most popular CSV parsing libraries for Clojure presently -- `clojure.data.csv` and `clojure-csv` -- are really focused on handling the _syntax_ of CSV;
;; They take CSV text and transform it into collections of row vectors of string values, providing a minimal translation into the world of data.
;; Semantic CSV takes it the next step by giving you tools for addressing the _semantics_ of your data, helping you put it into the form that better reflects what it means, and what's most useful for you.
;;
;; ## Features
;; 
;; To be less abstract about it, `semantic-csv` lets you easily:
;; 
;; * Absorb header row as a vector of column names, and return remaining rows as maps of `column-name -> row-val`
;; * Write from a collection of maps, given a pre-specified `:header`
;; * When reading, apply casting functions on a column by column basis (for casting to ints, floats, etc) via `:cast-fns`
;; * When writing, apply formatting functions on a column by column basis via `:format-fns`, when `str` won't cut it
;; * Remove lines starting with comment characters (by default `#`)
;; * A "sniffer" that reads in N lines, and uses them to guess column types (SOON)
;;
;; ## Structure
;;
;; Semantic CSV consists of a number of functions which perform separate processing steps towards your final
;; destination.
;; This is in the spirit of making the API as composable and interoperable as possible.
;; However, we also offer a magick sauce "do everything for me without making me think" function for the
;; impatient (see later).
;;
;; <br/>


(ns semantic-csv.core
  "# Core API namespace"
  (:require [clojure.java.io :as io]
            [clojure.data.csv :as csv]))


;; To start, require this namespace, as well as the namespace of your favorite CSV parser (e.g.,
;; [clojure-csv](https://github.com/davidsantiago/clojure-csv) or 
;; [clojure/data.csv](https://github.com/clojure/data.csv); we'll be using the former).
;; We'll also need `clojure.javas.io`.
;; 
;;     (require '[semantic-csv.core :as sc]
;;              '[clojure-csv :as csv]
;;              '[clojure.java.io :as io])
;;
;; Now let's take a tour through some of the processing functions we have available, starting with the reader
;; functions.

;; <br/>



;; # Reader functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; ## mappify-row

(defn mappify-row
  "Translates a single row of values into a map of `colname -> val`, given colnames in `header`."
  [header row]
  (into {} (map vector header row)))

;; We leave this in the main API as a courtesy in case you'd like to map lines over this function in your own
;; fashion.
;; However, in general, you'll want to use the following function:


;; ## mappify-csv-rows

(defn mappify
  "Comsumes the first item as a header, and returns a seq of the remaining items as a maps with the header
  values as keys (see mappify-row)."
  [rows]
  (let [header (first rows)]
    (map (partial mappify-row header) (rest rows))))

;; Here's an example to whet our whistle:
;;
;;     => (with-open [in-file (io/reader "test/test.csv")]
;;          (doall
;;            (->>
;;              (csv/parse-csv in-file)
;;              mappify)))
;;
;;     ({:this "# some comment lines..."}
;;      {:this "1", :that "2", :more "stuff"}
;;      {:this "2", :that "3", :more "other yeah"})
;;
;; Note that "# some comment lines..." was really intended to be left out of the _data_ as a comment.
;; We can solve this with the following function:

;; ## remove-comments

(defn remove-comments
  "Removes rows which start with a comment character (by default, `#`). Operates by checking the regular 
  expression against the first argument of every row in the collection."
  ([rows]
   (remove-comments #"^\#" rows))
  ([comment-re rows]
   (remove
     (fn [row]
       (let [x (first row)]
         (when x
           (re-find comment-re x)))))))

;; Let's see this in action with the above code:
;;
;;     => (with-open [in-file (io/reader "test/test.csv")]
;;          (doall
;;            (->>
;;              (csv/parse-csv in-file)
;;              remove-comments
;;              mappify)))
;;
;;     ({:this "1", :that "2", :more "stuff"}
;;      {:this "2", :that "3", :more "other yeah"})
;;
;; Much better :-)
;;
;; [**Sidenote**: it feels awkward to me that this operates _after_ the initial parsing step has already taken
;; place.
;; However, it's not clear how you would do this safely;
;; It seems you have to make assumptions about how  things are going into the parsing function, which I'd
;; rather avoid.]



(defn read-csv-rows
  "Given a `lines` collection, produces a seq of maps (`colname -> val`) where the column names are
  based on the first row's values.

  * `:header`: bool; consume the first row as a header?
  * `:comment-re`: specify a regular expression to use for commenting out lines, or something falsey
     if this isn't desired
  * `:remove-empty`: also remove empty rows?
  * `:cast-fns`: optional map of `colname | index -> cast-fn`; row maps will have the values as output by the
     assigned `cast-fn`.
              `(cast-fns row-name)` to the string val"
  [lines & {:keys [comment-re header remove-empty cast-fns]
                 :or   {comment-re   #"^\#"
                        header       true
                        remove-empty true
                        cast-fns     {}}
                 :as opts}]
  (let [non-cmnt-lines (remove #(or (when comment-re
                                      (re-find comment-re %))
                                    (when remove-empty
                                      (re-find #"^\s*$" %)))
                               lines)
        header (first (csv/read-csv (first non-cmnt-lines)))
        non-cmnt-lines (rest non-cmnt-lines)]
    (map
      (comp
        (partial read-csv-row
                 header 
                 (map #(or (cast-fns %) identity) header))
        first
        csv/read-csv)
      non-cmnt-lines)))


(defn read-csv-file
  "Read csv in from a filename or file handle. For details see the docstring for read-csv-rows"
  [file-or-filename & opts]
  (if (string? file-or-filename)
    (with-open [f (io/reader file-or-filename)]
      (doall
        ;; Trying out internal doc
        (impl/apply-kwargs read-csv-file f opts)))
    (impl/apply-kwargs read-csv-rows (line-seq file-or-filename) opts)))


(defn read-csv-str
  "Read csv in from a csv string. For details see the docstring for read-csv-rows"
  [csv-str & opts]
  (impl/apply-kwargs read-csv-rows (clojure.string/split-lines csv-str) opts))



;; # Some parsing functions for your convenience
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; These functions can be imported and used in your `:cast-fns` specification

(defn ->int
  "Translating string into integers"
  [string]
  (Integer/parseInt string))

(defn ->float
  "Translate into float"
  [string]
  (Float/parseFloat string))


;; # Writer functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;




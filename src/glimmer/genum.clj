(ns glimmer.genum
  "Resolve GTK/GObject GEnum members to their integer value at RUNTIME, from the
  GObject type registry — so glimmer carries NO enum-constant tables.

  Every GEnum type registers its members with an idiomatic lowercase *nick*
  (GtkAlign has :fill :start :center :end ...). That nick IS a Clojure keyword.
  We look it up via g_enum_get_value_by_nick and read the integer out of the
  returned GEnumValue struct, then hand the int to the C setter. So a prop like
  [:label {:halign :start}] needs no GTK_ALIGN_START constant anywhere.

  One wrinkle — lazy registration. A type only enters the registry once its
  _get_type() runs. For widget *property* enums that happens during the owning
  widget class's init: GtkWidget's init installs halign/valign -> registers
  GtkAlign; a label's init registers GtkJustification; a box's registers
  GtkOrientation. glimmer applies these props in the :apply step, by which point
  the widget already exists and its enum is live, so resolution is reliable
  there. The lone at-construction case (box orientation) is built with a default
  and corrected in :apply, where the freshly-created box has made the type live.

  Only GEnum/GFlags types resolve this way; plain #define numeric macros aren't
  in the registry and must be declared explicitly where truly needed (glimmer
  has none for layout)."
  (:require [glimmer.ffi :as g]
            [jolt.ffi :as ffi]))

;; GEnumValue = { gint value; const gchar *value_name; const gchar *value_nick; }
;; The integer we want is the first field, at byte offset 0.
(defn- enum-int [class-ptr nick-str]
  (let [v (g/g-enum-get-value-by-nick class-ptr nick-str)]
    (when-not (zero? v)
      (ffi/read v :int 0))))

(defn- resolve-enum [type-name nick]
  (let [nick-str (if (keyword? nick) (name nick) nick)
        t        (g/g-type-from-name type-name)]
    (when-not (zero? t)
      (enum-int (g/g-type-class-ref t) nick-str))))

;; Cache only successful resolutions (type-name + nick -> int). A nil result
;; (type not live yet, or unknown nick) is deliberately NOT cached: the type may
;; register later (lazy registration), so a retry must hit the registry again.
(def ^:private cache (atom {}))

(defn enum
  "Resolve a GEnum member nick to its integer value. `type-name` is the GObject
  type name (\"GtkAlign\"); `nick` is a keyword/string matching the member's nick
  (:start, \"start\"). Returns the int, or nil if the type isn't live yet or the
  nick is unknown. Successful lookups are memoized."
  [type-name nick]
  (let [k   [type-name (if (keyword? nick) (name nick) nick)]
        hit (get @cache k ::miss)]
    (if (not= hit ::miss)
      hit
      (let [v (resolve-enum type-name nick)]
        (when v (swap! cache assoc k v))
        v))))

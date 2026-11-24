(ns glimmer.morph-test
  "The reconciler behaviours a morphing client needs, the way Datastar morphs
  server-sent fragments into a live DOM: keyed and unkeyed children matched in
  one list, unchanged subtrees skipped, the previous props handed to the
  backend, and event handlers that follow the latest render."
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.backend :as b]
            [glimmer.core :as ui]
            [glimmer.mock-backend :as mock]
            [glimmer.ratom :as r]))

(defn- recording-backend
  "The mock backend, recording each [op tag] it is asked to do into `calls`."
  [calls]
  (reduce (fn [backend op]
            (let [f (get backend op)]
              (assoc backend op (fn [& args]
                                  (swap! calls conj [op (first args)])
                                  (apply f args)))))
          mock/backend
          [:create! :apply-props! :append-child! :remove-child! :replace-child! :reorder-child!]))

(defn- only-child [w] (first (mock/children w)))

(defn- labels [w] (mapv #(:label (:props @%)) (mock/children w)))

;; --- mixed keyed and unkeyed children ---------------------------------------

(deftest effective-keys-keeps-explicit-keys-and-numbers-the-rest-by-kind
  (is (= [[:glimmer.core/auto :label 0] "a" [:glimmer.core/auto :label 1]
          [:glimmer.core/auto :button 0] [:glimmer.core/auto :glimmer.core/text 0]]
         (ui/effective-keys [[:label {:label "h"}] [:label {:key "a"}] [:label {:label "f"}]
                             [:button {:label "b"}] "text"])))
  (testing "nil when explicit keys repeat"
    (is (nil? (ui/effective-keys [[:label {:key "a"}] [:label {:key "a"}]])))))

(deftest keyed-rows-between-unkeyed-siblings-keep-their-widgets
  (mock/install!)
  (let [rows (r/atom ["a" "b"]) c (mock/widget :box {})
        app (fn [] (into [:vbox [:label {:label "head"}]]
                         (concat (for [k @rows] [:label {:key k :label k}])
                                 [[:button {:label "foot"}]])))]
    (ui/mount c :box [app])
    (let [vbox (only-child c)
          ids-by-label #(into {} (map (fn [w] [(:label (:props @w)) (:id @w)]) (mock/children vbox)))
          before (ids-by-label)]
      (reset! rows ["c" "b" "a"])
      (is (= ["head" "c" "b" "a" "foot"] (labels vbox)))
      (let [after (ids-by-label)]
        (doseq [k ["head" "a" "b" "foot"]]
          (is (= (before k) (after k)) (str k " kept its widget")))))))

(deftest a-list-that-gains-its-first-key-keeps-its-unkeyed-widgets
  (mock/install!)
  (let [extra (r/atom false) c (mock/widget :box {})
        app (fn [] [:vbox [:label {:label "x"}] [:button {:label "y"}]
                    (when @extra [:label {:key "k" :label "k"}])])]
    (ui/mount c :box [app])
    (let [vbox (only-child c) before (mock/ids vbox)]
      (reset! extra true)
      (is (= ["x" "y" "k"] (labels vbox)))
      (is (= before (subvec (mock/ids vbox) 0 2))))))

(deftest repeated-keys-fall-back-to-positional-matching
  (mock/install!)
  (let [rows (r/atom [["a" 1] ["a" 2]]) c (mock/widget :box {})
        app (fn [] (into [:vbox] (for [[k n] @rows] [:label {:key k :label (str n)}])))]
    (ui/mount c :box [app])
    (reset! rows [["a" 3] ["a" 4] ["b" 5]])
    (is (= ["3" "4" "5"] (labels (only-child c))))))

;; --- skipping what did not change -------------------------------------------

(deftest a-component-with-equal-args-is-skipped-by-its-parents-pass
  (mock/install!)
  (let [tick (r/atom 0) arg (r/atom "a") own (r/atom 0) runs (atom 0) c (mock/widget :box {})
        child (fn [s] (swap! runs inc) [:label {:label (str s @own)}])
        app (fn [] @tick [:vbox [child @arg]])]
    (ui/mount c :box [app])
    (is (= 1 @runs))
    (swap! tick inc)
    (is (= 1 @runs) "equal args: not re-run")
    (reset! arg "b")
    (is (= 2 @runs) "changed args: re-run")
    (swap! own inc)
    (is (= 3 @runs) "its own cell: re-run")
    (is (= ["b1"] (labels (only-child c))))))

(deftest a-component-whose-render-threw-runs-again-on-the-next-pass
  (mock/install!)
  (let [tick (r/atom 0) fail? (atom true) runs (atom 0) c (mock/widget :box {})
        child (fn [] (swap! runs inc) (when @fail? (throw (ex-info "boom" {}))) [:label {:label "ok"}])
        app (fn [] @tick [:vbox [child]])]
    (is (thrown? Exception (ui/mount c :box [app])))
    (reset! fail? false)
    (ui/mount c :box [app])
    (swap! tick inc)
    (is (= ["ok"] (labels (only-child c))))))

(deftest equal-native-hiccup-touches-no-widget
  (let [calls (atom []) tick (r/atom 0) c (mock/widget :box {})
        rows [[:label {:label "a"}] [:label {:label "b"}]]
        app (fn [] [:vbox {:spacing @tick} (into [:vbox] rows)])]
    (b/register! (recording-backend calls))
    (ui/mount c :box [app])
    (reset! calls [])
    (swap! tick inc)
    (is (= [[:apply-props! :vbox]] @calls) "only the outer box, whose props changed")))

;; --- previous props ----------------------------------------------------------

(deftest patch-props-receives-the-props-the-last-render-applied
  (let [seen (atom []) text (r/atom "a") c (mock/widget :box {})]
    (b/register! (assoc mock/backend :patch-props!
                        (fn [tag w old new]
                          (swap! seen conj [tag (:text old) (:text new)])
                          ((:apply-props! mock/backend) tag w new))))
    (ui/mount c :box [(fn [] [:entry {:text @text}])])
    (reset! text "b")
    (reset! text "c")
    (is (= [[:entry "a" "b"] [:entry "b" "c"]] @seen))
    (is (= "c" (:text (:props @(only-child c)))))))

(deftest without-patch-props-every-prop-is-reapplied
  (mock/install!)
  (let [text (r/atom "a") c (mock/widget :box {})]
    (ui/mount c :box [(fn [] [:entry {:text @text :placeholder "p"}])])
    (reset! text "b")
    (is (= {:text "b" :placeholder "p"} (:props @(only-child c))))))

;; --- event handlers ----------------------------------------------------------

(deftest a-handler-connected-at-create-calls-the-latest-renders-handler
  (mock/install!)
  (let [target (r/atom :first) hits (atom []) c (mock/widget :box {})
        app (fn [] (let [t @target]
                     [:button {:label "go" :on-click #(swap! hits conj t)}]))]
    (ui/mount c :box [app])
    (let [btn (only-child c)
          connected (:on-click (:props @btn))]
      (connected)
      (reset! target :second)
      (is (identical? connected (:on-click (:props @btn))) "the backend keeps one handler")
      (connected)
      (is (= [:first :second] @hits)))))

(deftest a-handler-passes-the-backends-arguments-and-returns-its-value
  (mock/install!)
  (let [c (mock/widget :box {})]
    (ui/mount c :box [:entry {:on-change (fn [s] (str "got " s))}])
    (is (= "got x" ((:on-change (:props @(only-child c))) "x")))))

(deftest a-handler-a-later-render-drops-does-nothing
  (mock/install!)
  (let [on? (r/atom true) hits (atom 0) c (mock/widget :box {})
        app (fn [] [:button (cond-> {:label "go"} @on? (assoc :on-click #(swap! hits inc)))])]
    (ui/mount c :box [app])
    (let [connected (:on-click (:props @(only-child c)))]
      (reset! on? false)
      (connected)
      (is (zero? @hits)))))

(deftest non-handler-fn-props-pass-through-unwrapped
  (mock/install!)
  (let [f (fn [] :fmt) c (mock/widget :box {})]
    (ui/mount c :box [:label {:format f}])
    (is (identical? f (:format (:props @(only-child c)))))))

(deftest changed-props-keeps-what-differs-from-the-last-render
  (is (= {:b 3 :c 4} (b/changed-props {:a 1 :b 2} {:a 1 :b 3 :c 4})))
  (is (= {:a nil} (b/changed-props {} {:a nil})) "a newly given nil counts as changed")
  (is (= {} (b/changed-props {:a 1 :gone 2} {:a 1}))))

(deftest a-key-changed-only-in-metadata-is-not-skipped
  ;; = ignores metadata, so hiccup whose children differ only in ^{:key} would
  ;; look unchanged; the child with the new key must still get a new widget
  (mock/install!)
  (let [gen (r/atom 0) c (mock/widget :box {})
        app (fn [] [:vbox (with-meta [:label {:label "a"}] {:key ["a" @gen]})])]
    (ui/mount c :box [app])
    (let [before (mock/ids (only-child c))]
      (swap! gen inc)
      (is (not= before (mock/ids (only-child c))) "a new key, a new widget"))
    (is (ui/hiccup= [:a ^{:key 1} [:b]] [:a ^{:key 1} [:b]]))
    (is (not (ui/hiccup= [:a ^{:key 1} [:b]] [:a ^{:key 2} [:b]])))))

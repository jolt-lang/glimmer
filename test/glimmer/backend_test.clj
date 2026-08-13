(ns glimmer.backend-test
  "The backend seam itself: registration, dispatch, the optional keys, and the
  error a missing backend produces. Everything a new backend (Qt, TUI, …) has to
  satisfy is asserted here against a recording stub."
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.backend :as b]
            [glimmer.mock-backend :as mock]))

(defn- recorder
  "A backend that records every call as [op & args] into `log`, plus whatever
  extra keys the test needs."
  [log & {:as extra}]
  (merge {:name :recorder
          :create!        (fn [tag props] (swap! log conj [:create! tag props]) :widget)
          :apply-props!   (fn [tag w props] (swap! log conj [:apply-props! tag w props]))
          :append-child!  (fn [pt p c] (swap! log conj [:append-child! pt p c]))
          :remove-child!  (fn [pt p c] (swap! log conj [:remove-child! pt p c]))
          :replace-child! (fn [pt p o n] (swap! log conj [:replace-child! pt p o n]))}
         extra))

(deftest register-installs-the-backend
  (let [log (atom [])]
    (b/register! (recorder log))
    (is (true? (b/installed?)))
    (is (= :recorder (:name (b/current))))
    (b/register! nil)
    (is (false? (b/installed?)))
    ;; leave a working backend behind for whatever runs next
    (mock/install!)))

(deftest calls-are-dispatched-to-the-installed-backend
  (let [log (atom [])]
    (b/register! (recorder log))
    (is (= :widget (b/create! :label {:label "x"})))
    (b/apply-props! :label :w {:label "y"})
    (b/append-child! :box :parent :child)
    (b/remove-child! :box :parent :child)
    (b/replace-child! :box :parent :old :new)
    (is (= [[:create! :label {:label "x"}]
            [:apply-props! :label :w {:label "y"}]
            [:append-child! :box :parent :child]
            [:remove-child! :box :parent :child]
            [:replace-child! :box :parent :old :new]]
           @log))
    (mock/install!)))

(deftest optional-keys-have-sane-defaults
  (let [log (atom [])]
    (testing "a backend without :reorder-child! simply doesn't reorder"
      (b/register! (recorder log))
      (is (nil? (b/reorder-child! :box :parent :child nil)))
      (is (= [] @log)))
    (testing "without :schedule, work runs inline"
      (let [ran (atom 0)]
        (b/schedule (fn [] (swap! ran inc)))
        (is (= 1 @ran))))
    (testing "a bare string renders as a label by default"
      (is (= [:label {:label "hi"}] (b/text->element "hi"))))
    (testing "a backend may say how text renders"
      (b/register! (recorder log :text->element (fn [s] [:text {:value s}])))
      (is (= [:text {:value "hi"}] (b/text->element "hi"))))
    (mock/install!)))

(deftest a-missing-backend-fails-loudly
  (testing "nothing registered"
    (b/register! nil)
    (is (thrown? Exception (b/create! :label {}))))
  (testing "registered but missing a required op"
    (b/register! {:name :partial})
    (is (thrown? Exception (b/create! :label {}))))
  (mock/install!))

(deftest loop-running-defaults-to-false
  ;; The reconciler renders inline until a backend's event loop takes the main
  ;; thread, which is what makes the headless tests synchronous.
  (is (false? @b/loop-running?)))

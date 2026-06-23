(ns glimmer.todo
  "A todo list — exercises a derived reaction (remaining count), an entry with
  :on-change, and list rendering. Clicking add appends an item and the reaction
  re-derives. Try `joltc todo` (the :todo task)."
  (:require [glimmer.ratom :as r :refer [atom reaction]]
            [glimmer.core :as ui]))

(defn todo-app []
  (let [state (atom [{:text "try glimmer" :done false}
                     {:text "ship it"      :done false}])
        draft (atom "")
        remaining (reaction (count (remove :done @state)))]
    (fn []
      [:vbox {:spacing 12}
       [:label {:label (str @remaining " items left")}]
       (for [{:keys [text done]} @state]
         [:label {:label (str (if done "☑ " "☐ ") text)}])
       [:hbox {:spacing 8}
        [:entry {:text @draft :on-change #(reset! draft %)}]
        [:button {:label "add"
                  :on-click (fn []
                              (when (seq @draft)
                                (swap! state conj {:text @draft :done false})
                                (reset! draft "")))}]]])))

(defn -main [& _]
  (ui/run todo-app :title "glimmer todo" :width 400 :height 320 :app-id "glimmer.todo"))

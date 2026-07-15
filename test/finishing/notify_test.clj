(ns finishing.notify-test
  (:require [clojure.test :refer [deftest is]]
            [finishing.notify :as notify]))

(deftest mock-notifier-records-mail-and-phone-sends
  (let [n (notify/mock-notifier)]
    (notify/-send-mail! n {:to "a@example.com" :subject "s" :body "b"})
    (notify/-send-phone-call! n {:to "+1555" :message "m"})
    (is (= [{:status :sent :channel :mail :to "a@example.com" :subject "s" :body "b"}
            {:status :sent :channel :phone :to "+1555" :message "m"}]
           (notify/sent-log n)))))

(deftest dispatch-safety-concern-notice-fans-out-to-every-contact-both-channels
  (let [n (notify/mock-notifier)
        contacts [{:name "Tanaka" :email "tanaka@example.com" :phone "+819000000001"}
                  {:name "Suzuki" :email "suzuki@example.com" :phone "+819000000002"}]
        result (notify/dispatch-safety-concern-notice! n contacts {:subject-line "CONCERN" :body "check now" :message "check now"})]
    (is (= 2 (count result)))
    (is (every? #(= :sent (get-in % [:mail :status])) result))
    (is (every? #(= :sent (get-in % [:phone :status])) result))
    (is (= 4 (count (notify/sent-log n))) "2 contacts x 2 channels")))

(deftest dispatch-safety-concern-notice-isolates-one-contacts-failure-from-the-rest
  (let [failing-once (atom false)
        n (reify notify/Notifier
            (-send-mail! [_ {:keys [to]}]
              (if (and (not @failing-once) (= to "bad@example.com"))
                (do (reset! failing-once true) (throw (ex-info "boom" {})))
                {:status :sent :channel :mail :to to}))
            (-send-phone-call! [_ {:keys [to]}] {:status :sent :channel :phone :to to}))
        contacts [{:name "Bad" :email "bad@example.com" :phone "+1"}
                  {:name "Good" :email "good@example.com" :phone "+2"}]
        result (notify/dispatch-safety-concern-notice! n contacts {:subject-line "s" :body "b" :message "m"})]
    (is (= :failed (get-in (first result) [:mail :status])))
    (is (= :sent (get-in (second result) [:mail :status])) "one contact's failure must not suppress the next contact's send")))

;; ----------------------------- injectable real transport (fn-notifier, no JVM interop) -----------------------------

(deftest fn-notifier-delegates-to-injected-functions
  (let [captured-mail (atom nil)
        captured-phone (atom nil)
        n (notify/fn-notifier
           {:send-mail-fn (fn [msg] (reset! captured-mail msg) {:status :sent :channel :mail :to (:to msg) :provider "injected"})
            :send-phone-fn (fn [msg] (reset! captured-phone msg) {:status :sent :channel :phone :to (:to msg) :provider "injected"})})
        mail-result (notify/-send-mail! n {:to "engineer@example.com" :subject "CONCERN" :body "check now"})
        phone-result (notify/-send-phone-call! n {:to "+819000000001" :message "至急ご確認ください"})]
    (is (= "engineer@example.com" (:to @captured-mail)))
    (is (= "至急ご確認ください" (:message @captured-phone)))
    (is (= :sent (:status mail-result)))
    (is (= :sent (:status phone-result)))
    (is (= "injected" (:provider mail-result)))))

(deftest fn-notifier-propagates-a-failing-injected-function
  (let [n (notify/fn-notifier
           {:send-mail-fn (fn [_] (throw (ex-info "transport down" {})))
            :send-phone-fn (fn [msg] {:status :sent :channel :phone :to (:to msg)})})]
    (is (thrown? Exception (notify/-send-mail! n {:to "e@example.com" :subject "s" :body "b"})))))

(deftest dual-notifier-composes-a-mail-only-and-a-phone-only-notifier
  (let [mail-log (atom [])
        phone-log (atom [])
        mail-only (reify notify/Notifier
                    (-send-mail! [_ m] (swap! mail-log conj m) {:status :sent :channel :mail :to (:to m)})
                    (-send-phone-call! [_ _] (throw (ex-info "mail-only" {}))))
        phone-only (reify notify/Notifier
                    (-send-mail! [_ _] (throw (ex-info "phone-only" {})))
                    (-send-phone-call! [_ m] (swap! phone-log conj m) {:status :sent :channel :phone :to (:to m)}))
        dual (notify/dual-notifier mail-only phone-only)]
    (notify/-send-mail! dual {:to "a@example.com" :subject "s" :body "b"})
    (notify/-send-phone-call! dual {:to "+1" :message "m"})
    (is (= 1 (count @mail-log)))
    (is (= 1 (count @phone-log)))))

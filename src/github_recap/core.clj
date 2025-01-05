(ns github-recap.core
  (:gen-class)
  (:import [java.time LocalDate])
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.adapter.jetty :as jetty]
            [clj-http.client :as client]
            [cheshire.core :refer :all]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [ring.util.response :refer [response]]
            [selmer.parser :refer [render-file]]
            [environ.core :refer [env]]))

;; Constants
(def date-formatter (f/formatter "yyyy-MM-dd"))

(def month-map
  {1 "January"
   2 "February"
   3 "March"
   4 "April"
   5 "May"
   6 "June"
   7 "July"
   8 "August"
   9 "September"
   10 "October"
   11 "November"
   12 "December"})

(def day-map
  {1 "Monday"
   2 "Tuesday"
   3 "Wednesday"
   4 "Thursday"
   5 "Friday"
   6 "Saturday"
   7 "Sunday"})

; The function send-github-graphql-request sends a GraphQL request to the GitHub API.
; It takes a GitHub personal access token and a username as arguments.
; The function constructs a GraphQL query that fetches the user's contribution calendar and repository information.
(defn send-github-graphql-request [token username]
  (let [query "query ($username: String!) {
  user(login: $username) {
    name
    bio
    avatarUrl
    url
    followers {
      totalCount
    }
    following {
      totalCount
    }
               
               pinnedItems(first: 6, types: REPOSITORY) {
      totalCount
      nodes {
        ... on Repository {
          name
          description
          stargazerCount
          forkCount
          primaryLanguage {
            name
            color
          }
          url
        }
      }
    }

    contributionsCollection {
      contributionCalendar {
        totalContributions
        weeks {
          contributionDays {
            contributionCount
            date
            weekday
          }
        }
      }
    }
    repositories(first: 100, orderBy: { field: STARGAZERS, direction: DESC }) {
      nodes {
        stargazerCount
        primaryLanguage {
          name
        }
      }
    }
  }
}
"
        variables {:username username}
        body (cheshire.core/generate-string {:query query :variables variables})
        response (client/post "https://api.github.com/graphql"
                              {:headers {"Authorization" (str "Bearer " token)
                                         "Content-Type" "application/json"}
                               :body body
                               :as :json})]

    response

    ;; Only extract the :body of the response, which is the actual JSON content
    ))

; The function get-monthly-commits takes a list of contribution days and returns a map of monthly commits.
; The map keys are the month numbers (1-12), and the values are the total number of commits for that month.
(defn get-monthly-commits [contribution-days]
  (reduce (fn [acc day]
            (let [date-str (:date day)
                  parsed-date (f/parse date-formatter date-str)
                  month (.getMonthOfYear parsed-date)
                  month-key (str month)
                  count (:contributionCount day)]

              (update acc month-key (fnil + 0) count)))
          {}
          contribution-days))

; The function get-daily-commits takes a list of contribution days and returns a map of daily commits.
; The map keys are the weekday numbers (1-7), and the values are the total number of commits for that day.
(defn get-daily-commits [contribution-days] (reduce (fn [acc day] (let [date-str (:date day)
                                                                        parsed-date (f/parse date-formatter date-str)
                                                                        weekday (.getDayOfWeek parsed-date)
                                                                        weekday-key (str weekday)
                                                                        count (:contributionCount day)]
                                                                    (update acc weekday-key (fnil + 0) count))) {} contribution-days))

(defn get-most-active-month [monthly-commits]
  (get month-map (Integer/parseInt (first (apply max-key val (seq monthly-commits))))))

(defn get-most-active-day [daily-commits]   (get day-map (Integer/parseInt (first (apply max-key val (seq daily-commits))))))

(defn get-total-stars [repositories]
  (let [{:keys [nodes]} repositories]
    (reduce (fn [acc repo] (let [stargazers (:stargazerCount repo)]
                             (+ acc stargazers))) 0 nodes)))

(defn get-top-language [repositories]
  (let [{:keys [nodes]} repositories]
    (->> (reduce (fn [acc repo]
                   (let [language (:name (:primaryLanguage repo))]
                     (if language
                       (update acc language (fnil + 0) 1)
                       acc)))
                 {}
                 nodes) (sort-by val >) (take 3))))

(defn get-max-streak [contribution-days]
  (let [result (reduce
                (fn [[current-streak max-streak] day]
                  (if (> (:contributionCount day) 0)
                    [(inc current-streak) (max max-streak (inc current-streak))]
                    [0 max-streak]))
                [0 0]
                contribution-days)]
    (second result)))

(defn get-profile [user repositories]
  (let [{:keys [name bio avatarUrl url followers following pinnedItems]} user]
    {:name name
     :bio bio
     :avatarUrl avatarUrl
     :url url
     :publicRepos (count (:nodes repositories))
     :pinnedItems (get-in pinnedItems [:nodes])
     :followers (get-in followers [:totalCount])
     :following (get-in following [:totalCount])}))

(defn get-stats [data] (let [start-date (f/parse date-formatter "2024-01-01") ; YEAR START
                             end-date (f/parse date-formatter "2024-12-31") ; YEAR END
                             {:keys [data]} data
                             {:keys [user]} data
                             {:keys [repositories]} user
                             {:keys [contributionsCollection]} user
                             {:keys [contributionCalendar]} contributionsCollection
                             {:keys [weeks]} contributionCalendar
                             user-profile (get-profile user repositories)
                             contribution-days (mapcat :contributionDays weeks)
                             filtered-days (filter #(let [contrib-date (f/parse date-formatter (:date %))]
                                                      (t/within? (t/interval start-date end-date) contrib-date))
                                                   contribution-days)
                             monthly-commits (get-monthly-commits filtered-days)
                             total-commits (-> contributionsCollection :contributionCalendar :totalContributions)
                             most-active-month (get-most-active-month monthly-commits)
                             most-active-day (get-most-active-day (get-daily-commits filtered-days))
                             total-stars (get-total-stars repositories)
                             top-language (get-top-language repositories)
                             max-streak (get-max-streak filtered-days)]

                         {:total-commits total-commits
                          :most-active-month most-active-month
                          :most-active-day most-active-day
                          :total-stars total-stars
                          :top-language top-language
                          :max-streak max-streak
                          :user-profile user-profile}))

(defn graphql-response-handler [request]
  (let [github-token (env :github-personal-access-token)
        github-username (get-in request [:params :username])
        response (send-github-graphql-request github-token github-username)
        error (-> response :body :errors first :message)]

    (println "username: " github-username)
    (if error (println "Error: " error))

    (println (get-stats (:body response)))

    (if error
      {:error error}
      (get-stats (:body response)))))

;; (if error ;; Check if there is an error in the response
    ;;   {:status 400
    ;;    :headers {"Content-Type" "application/json"}
    ;;    :body (generate-string {:error error})}
    ;;   {:status 200
    ;;    :headers {"Content-Type" "application/json"}
    ;;    :body (generate-string (get-stats (:body response)))})))

;; Return the response as JSON string
(defn page-handler [username]
  (let [context (graphql-response-handler {:params {:username username}})]
    (-> (render-file "templates/index.html" context)
        response)))

;; Define routes
(defroutes app-routes
  (GET "/" [] (page-handler "sagargajare"))
  (GET "/:username" [username] (page-handler username))
  (GET "/t/:username" [username] (graphql-response-handler {:params {:username username}}))

  (route/not-found "Not Found"))

;; Start the server
(defn -main []
  (jetty/run-jetty app-routes {:port 3000 :join? false}))

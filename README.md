# Where is my bus?

## A hackakl project

This is my attempt to use clojurescript and mapbox to build a client-side
implementation of a realtime bus location UI for Auckland.

The realtime feed comes from Auckland transport https://api.at.govt.nz/
(this project also currently includes my api key so don't abuse it).

## Rough design
https://docs.google.com/presentation/d/1Oi3bfS2NPGHlJO0ouPp7jbQ_DKWrRbXa_tXn8bCQbi4/edit?usp=sharing

## Building

1. Install Leiningen http://leiningen.org/
2. In the root dir build the project
        ```
        lein cljsbuild auto hackakl_where_is_my_bus
        ```
3. Open index.html in the browser


## Live
Live at http://goo.gl/iioxPo
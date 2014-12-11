# CI
Uses [Gulp](http://gulpjs.com/) for build and development tasks (see gulpfile.js for details).

  > npm install

will install required modules to work with javascript and css of the project.

# Applications

 * [React](http://facebook.github.io/react/)
 * [Less](http://lesscss.org/)

# Modules

[Browserify](http://browserify.org/)

# SVG

<link rel="stylesheet" type="text/css" href="./node_modules/evil-icons/app/assets/stylesheets/evil-icons.css">

var icons = require("evil-icons")

/* A string with SVG sprite */
icons.sprite;

/* Icons rendering */
icons.icon("ei-search");
icons.icon("ei-arrow-right", {size: "m"});
icons.icon("ei-envelope", {size: "l", class: "custom-class"});

icons.icon("ei-arrow-right", {size: "m"})
icons.icon("ei-envelope", {class: "custom-class"})

.icon {fill: green;}
.icon--ei-facebook {  fill: blue;}
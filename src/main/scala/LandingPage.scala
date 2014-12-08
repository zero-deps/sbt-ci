package ci.sbt

import akka.http.model.MediaTypes._
import akka.http.model.{HttpEntity, HttpResponse}
import sbt.AutoPlugin

trait LandingPage { this:AutoPlugin =>


  def index:HttpResponse = HttpResponse(entity=HttpEntity(`text/html`,
    s"""
      |<!DOCTYPE html>
      |<html lang="en">
      |<head>
      | <meta charset="utf-8">
      | <meta http-equiv="X-UA-Compatible" content="IE=edge">
      | <meta name="viewport" content="width=device-width, initial-scale=1">
      | <!--meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"-->
      | <meta name="apple-mobile-web-app-capable" content="yes" />
      | <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />
      |
      | <title>ci</title>
      | <link rel="stylesheet" href="assets/css/reveal.min.css">
      |	<link rel="stylesheet" href="assets/css/default.css" id="theme">
      | <link rel="stylesheet" href="assets/css/ci.css" />
      | <script type="text/javascript" src="assets/js/react.js"></script>
      |</head>
      |<body>
      | <div class="page">
      |   <nav class="navbar navbar-default navbar-fixed-top">
      |     <div class="container">
      |       <div class="navbar-header">
      |         <a class="navbar-brand" href="#">mws</a>
      |       </div>
      |     </div>
      |   </nav>
      |   <div class="page-wrapper">
      |     <div class="container">
      |       <div id="bdy"></div>
      |       <div class="embed-responsive embed-responsive-16by9">${reveal(Seq("assets/md/example.md"))}</div>
      |     </div>
      |   </div>
      | <div>
      | <footer class="section">mws(c)2014</footer>
      | <script type="text/javascript" src="assets/js/ci.js"></script>
      |</body>
      |</html>""".stripMargin('|')))

  def reveal(sections:Seq[String]):String =
    s"""
      |<div class="reveal embed-responsive-item"><div class="slides">
        ${sections.foldLeft[String]("")((a, sc) =>
          a ++ s"""|<section data-markdown="$sc" data-separator="^\n\n\n" data-vertical="^\n\n"></section>"""
        )}
      |</div></div>
      |<script src="assets/js/head.min.js"></script>
      |<script src="assets/js/reveal.min.js"></script>
    """.stripMargin('|')
}

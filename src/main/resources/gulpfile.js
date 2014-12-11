var gulp = require('gulp')

var del         = require('del');
var browserify  = require('browserify');
var watchify    = require('watchify');
var source      = require('vinyl-source-stream');
var streamify   = require('gulp-streamify');
var concat      = require('gulp-concat');
var uglify      = require('gulp-uglify');
var reactify    = require('reactify')
var less        = require('gulp-less');
var path        = require('path');
var lessClean   = require("less-plugin-clean-css");
var cleancss    = new lessClean({advanced: true});
var sourcemaps = require('gulp-sourcemaps');

var prod = false;
var conf = {
  jsx : ['./js/ci.js'],
  less: ['./less/ci.less', './less/default.less'],
  css:['./assets/css/'],
  js: ['./assets/js/'],
  maps: './maps'};

function scr(watch){
  var bnd,re;
  bnd = browserify({
    entries: conf.jsx,
    transform: [reactify],
    debug: !prod, cache: {}, packageCache: {}, fullPaths: watch});

  if(watch) bnd = watchify(bnd);

  re = function(){
    console.log('reload js');
    return bnd.bundle()
      .pipe(source('ci.js'))
      .pipe(streamify(uglify()))
      .pipe(gulp.dest(conf.js[0]));
  };

  bnd.on('update', re);
  return re();
};

gulp.task('clean-js',function(js){ del(conf.js, js); });
gulp.task('cp-js', function(){ 
  return gulp.src(['./js/**/*.js', '!./js/ci.js'])
    .pipe(gulp.dest(conf.js[0])) 
});
gulp.task('js', ['clean-js', 'cp-js'], function(){return scr(false);});

gulp.task('clean-css',function(css){del(conf.css, css);});
gulp.task('less',['clean-css'],function(){
  return gulp.src(conf.less)
//    .pipe(sourcemaps.init())
    .pipe(less({
//      plugins: [cleancss],
      paths: [path.join(__dirname, 'less', 'includes')]
    }))
//    .pipe(sourcemaps.write(conf.maps))
    .pipe(gulp.dest(conf.css[0]));
});
gulp.task('css', function(){
  gulp.watch(conf.less, ['less']);
});

gulp.task('default', ['js','less']);
gulp.task('watch', ['css'], function(){return scr(true);});

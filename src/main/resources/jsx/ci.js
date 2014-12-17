var ws = {};
ws.get = function(url,callback){
  var req = new XMLHttpRequest();
  req.onreadystatechange = function() {
      if (req.readyState == 4 && req.status == 200) {callback(req.response);}
  };
  req.open('GET', url, true);
  req.send();
};

var React = require('react');
var View = require('./view.jsx');

var data2 = [
  {name: "task1", text: "This is one"},
  {name: "task2", text: "This is *another*"}
];

var SbtTask = React.createClass({
  render: function(){
    return <li>{this.props.task.text}</li>;
  }
});

var SbtTasks = React.createClass({
  render: function(){
    return (
      <div>
        {this.props.data.map(function(item){
          return <SbtTask key={item.name} task={item}/>;
        })}
      </div>
    );
  }
});


React.render(
  <SbtTasks data={data2}/>,
  document.getElementById('bdy'));

/*Reveal.initialize({history: true,center: true,embedded: true,
  dependencies: [
    {src: 'assets/js/marked.js', condition: function() { return !!document.querySelector('[data-markdown]');}},
    {src: 'assets/js/markdown.js', condition: function() { return !!document.querySelector('[data-markdown]');}} ]});*/
//React.render(<View/>, document.body);
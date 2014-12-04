var data = [
  {name: "task", text: "This is one"},
  {name: "another task", text: "This is *another*"}
];

var sbt_task = React.createClass({displayName:'task',
  render: function(){
    return React.createElement('li', null, this.props.name);
  }
});

var sbt_tasks = React.createClass({displayName:'tasks',
  render: function(){
    var taskNodes = this.props.data.map(function(task){
      return (
        React.createElement(sbt_task,{name:task.name})
      );
    });
    return (
      React.createElement('div', {className:"sbt tasks"}, taskNodes)
    );
  }
});


React.render(React.createElement(sbt_tasks, {data:data}), document.getElementById('bdy'));

Reveal.initialize({history: true,center: true,embedded: true,
  dependencies: [
    {src: 'assets/js/marked.js', condition: function() { return !!document.querySelector('[data-markdown]');}},
    {src: 'assets/js/markdown.js', condition: function() { return !!document.querySelector('[data-markdown]');}} ]});

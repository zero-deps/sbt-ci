var ballmer_peak = React.createClass({displayName: 'balmer_peak',
  getInitialState: function() {
    return {bac: 0};
  },
  handleChange: function(event) {
    this.setState({bac: event.target.value});
  },
  render: function() {
    var pct = this.state.bac;
    return (
      React.createElement("input", {type: "text", onChange: this.handleChange, value: this.state.bac}),
      React.createElement("b", null, pct)
    );
  }
});

React.render(React.createElement(ballmer_peak), document.getElementById('bdy'));

Reveal.initialize({history: true,center: true,embedded: true,
  dependencies: [
    {src: 'assets/js/marked.js', condition: function() { return !!document.querySelector('[data-markdown]');}},
    {src: 'assets/js/markdown.js', condition: function() { return !!document.querySelector('[data-markdown]');}} ]});

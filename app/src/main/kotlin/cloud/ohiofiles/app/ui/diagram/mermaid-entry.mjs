import mermaid from 'mermaid';

const secureKeys = ['secure','securityLevel','startOnLoad','maxTextSize','maxEdges','suppressErrorRendering','dompurifyConfig','themeCSS','themeVariables','fontFamily','htmlLabels','flowchart'];
let result = {status:'ready'};
window.OhioMermaid = {
  poll() { return result; },
  async render(request) {
    if (result.status === 'rendering') return;
    result = {status:'rendering'};
    try {
      let source = String(request.source || '').trim().replace(/%%\{[\s\S]*?\}%%/g,'').trim().replace(/^---[ \t]*\r?\n[\s\S]*?\r?\n---[ \t]*(?:\r?\n|$)/,'').trim();
      if (!source) throw new Error('The diagram is empty.');
      if (source.length > 50000) throw new Error('Diagrams support up to 50,000 characters.');
      const arrows = source.match(/-->|---|==>|-\.->|->>|-->>|\.\.>|=>|--\|>/g) || [];
      if (arrows.length > 500) throw new Error('Diagrams support up to 500 edges.');
      const dark = request.dark === true;
      mermaid.initialize({securityLevel:'strict', startOnLoad:false, suppressErrorRendering:true, maxTextSize:50000, maxEdges:500, htmlLabels:false,
        secure:secureKeys, theme:dark?'dark':'default', fontFamily:'sans-serif', flowchart:{htmlLabels:false},
        themeVariables:{background:'transparent',darkMode:dark,primaryColor:dark?'#263349':'#e8f0ff',primaryTextColor:dark?'#eef3ff':'#172238',lineColor:dark?'#b8c9e8':'#526079',fontFamily:'sans-serif'},
        deterministicIds:true, deterministicIDSeed:'ohiofiles-native'});
      const rendered = await mermaid.render('ohio-native-diagram',source);
      if (typeof rendered.svg !== 'string' || rendered.svg.length > 4000000) throw new Error('The rendered diagram exceeds the preview limit.');
      result = {status:'complete',svg:rendered.svg};
    } catch (error) { result = {status:'error',message:String(error?.message || 'The diagram could not render.').slice(0,300)}; }
    finally { document.getElementById('ohio-native-diagram')?.remove(); }
  }
};

window.OhioMermaid.snapshot = function(svg) {
  if (typeof svg !== 'string' || svg.length > 4000000) throw new Error('Invalid diagram snapshot');
  document.body.innerHTML = svg;
  const root = document.body.querySelector('svg');
  if (!root) throw new Error('Missing SVG diagram');
  const box = root.viewBox?.baseVal;
  const rawWidth = box?.width || parseFloat(root.getAttribute('width')) || 800;
  const rawHeight = box?.height || parseFloat(root.getAttribute('height')) || 600;
  const scale = Math.min(1600/rawWidth,1600/rawHeight,2);
  const width = Math.max(1,Math.min(1600,Math.round(rawWidth*scale)));
  const height = Math.max(1,Math.min(1600,Math.round(rawHeight*scale)));
  root.style.maxWidth='none'; root.style.width=width+'px'; root.style.height=height+'px';
  document.body.style.width=width+'px';document.body.style.height=height+'px';
  return JSON.stringify({width,height});
};
import 'katex/dist/katex.min.css';

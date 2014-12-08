package ci.sbt

/**
 * Created by doxtop on 12/6/14.
 */
class VRender {
  implicit val title = "vt"


  def questionHTML(i: Int, q: String) =
    <span>
      <h3>Question {i+1}</h3>{q}
      <br/><br/><br/><br/><br/><br/>
    </span>

  def variantHTML(i: Int, v: List[String]) =
    <p style="page-break-after:always;">
      <h1><center>{title}</center></h1>
      {v.zipWithIndex map {case(q,j) => questionHTML(j,q)}}
    </p>

  def html(variants: List[List[String]]) = {
    <html><body>{
      variants.zipWithIndex map { case (v, i) => variantHTML(i, v) }
    }</body></html>
  }
}

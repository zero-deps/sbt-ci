package ci

import akka.http.model.{ResponseEntity, StatusCode}

package object sbt{
  /**
   * target:
   * - assets
   * - resolution-cache
   * - scala
   * - streams
   *
   * streams: configuration scope
   * - $global
   * - compile
   * - runtime
   * - test
   *
   * $global/streams - for tasks under scope
   *
   * files:
   * - export
   * - out
   *
   * - input_dsp, output_dsp,inputs,output
   *
   * update_cache_2.11
   *
   * resolution-cache / reports
   *
   * apache-tika metadata parsers
   */
  type Segment = String
  type FileResponce = Either[StatusCode, ResponseEntity]

  import akka.http.model.Uri

  sealed trait Path
  object Root extends Path
  case class SegmentPath(prev: Path, head: Segment) extends Path

  object Path{
    def unapply(uri: Uri): Option[Path] = {
      def path(p: Uri.Path): Path = {
        p.reverse
        p match {
          case Uri.Path.Empty => Root
          case Uri.Path.Segment(head, slashOrEmpty) => SegmentPath(path(slashOrEmpty), head)
          case Uri.Path.Slash(tail) => path(tail)
        }
      }
      if(uri.isEmpty) None else Option(path(uri.path.reverse))
    }
  }

  object / {
    def unapply(l: Path): Option[(Path, String)] = l match {
      case Root => None
      case s: SegmentPath => Option((s.prev, s.head))
    }
  }
}

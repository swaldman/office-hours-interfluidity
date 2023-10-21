import $meta._

import mill._
import mill.scalalib._
import mill.scalalib.publish._

import $ivy.`com.mchange::untemplate-mill:0.1.0`
import untemplate.mill._

object OfficeHoursInterfluidity extends RootModule with UntemplateModule with PublishModule {

  override def scalaVersion = "3.3.1"

//  def scalacOptions = T {
//    super.scalacOptions() ++ Seq("-explain")
//  }

  override def artifactName = "office-hours-interfluidity"
  override def publishVersion = T{"0.0.1-SNAPSHOT"}
  override def pomSettings    = T{
    PomSettings(
      description = "Automate management of interfluidity office hours",
      organization = "com.interfluidity",
      url = s"https://github.com/swaldman/${artifactName}",
      licenses = Seq(License.`Apache-2.0`),
      versionControl = VersionControl.github("swaldman", s"$artifactName"), // to trick an automatic conversion to Target[String]? a bit weird.
      developers = Seq(
	      Developer("swaldman", "Steve Waldman", "https://github.com/swaldman")
      )
    )
  }
  
  override def ivyDeps = T{
    super.ivyDeps() ++ Agg(
      ivy"com.mchange::mchange-sysadmin-scala:0.1.2-SNAPSHOT",
      ivy"com.lihaoyi::requests:0.8.0",
    )
  }

  // we'll build an index!
  override def untemplateIndexNameFullyQualified : Option[String] = Some("com.interfluidity.officehours.IndexedUntemplates")

  override def untemplateSelectCustomizer: untemplate.Customizer.Selector = { key =>
    var out = untemplate.Customizer.empty

    // to customize, examine key and modify the customer
    // with out = out.copy=...
    //
    // e.g. out = out.copy(extraImports=Seq("commchangesysadmin.*"))

    out
  }
}



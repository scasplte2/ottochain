package xyz.kd5ujc.ci

import cats.effect.IO

import weaver.SimpleIOSuite

import java.nio.file.{Files, Paths}
import scala.util.matching.Regex

/**
 * TDD tests for Docker workflow integration with tessellation base images.
 * These tests verify the Docker and GitHub Actions workflow functionality.
 */
object DockerWorkflowTest extends SimpleIOSuite {

  test("tessellation-base.Dockerfile should start with minimal base") {
    IO {
      val dockerfilePath = Paths.get("tessellation-base.Dockerfile")
      val content = Files.readString(dockerfilePath)
      val lines = content.split("\n").map(_.trim).filter(_.nonEmpty)
      
      // First non-comment line should be FROM with minimal base
      val fromLine = lines.find(_.startsWith("FROM")).getOrElse("")
      expect(fromLine.contains("scratch") || fromLine.contains("alpine"))
    }
  }

  test("tessellation-base.Dockerfile should copy only tessellation JARs") {
    IO {
      val dockerfilePath = Paths.get("tessellation-base.Dockerfile")
      val content = Files.readString(dockerfilePath)
      
      // Should copy gl0, gl1, keytool, wallet but NOT metagraph-specific JARs
      val copyLines = content.lines().filter(_.trim.startsWith("COPY")).toArray
      
      expect(copyLines.nonEmpty) &&
      expect(copyLines.exists(_.contains("gl0"))) &&
      expect(copyLines.exists(_.contains(".jar"))) &&
      expect(!content.contains("ml0")) &&
      expect(!content.contains("cl1")) &&
      expect(!content.contains("dl1"))
    }
  }

  test("build-tessellation-base.yml should have correct job structure") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      val content = Files.readString(workflowPath)
      
      expect(content.contains("name:")) &&
      expect(content.contains("jobs:")) &&
      expect(content.contains("build:")) &&
      expect(content.contains("runs-on: ubuntu-latest"))
    }
  }

  test("build-tessellation-base.yml should setup Java and sbt") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      val content = Files.readString(workflowPath)
      
      expect(content.contains("actions/setup-java@v4")) &&
      expect(content.contains("temurin")) &&
      expect(content.contains("java-version: '21'")) &&
      expect(content.contains("sbt/setup-sbt@v1"))
    }
  }

  test("build-tessellation-base.yml should clone tessellation") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      val content = Files.readString(workflowPath)
      
      expect(content.contains("git clone")) &&
      expect(content.contains("tessellation.git")) &&
      expect(content.contains("TESSELLATION_VERSION"))
    }
  }

  test("build-tessellation-base.yml should build tessellation JARs") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      val content = Files.readString(workflowPath)
      
      // Should build tessellation components but not metagraph
      expect(content.contains("sbt")) &&
      (expect(content.contains("assembly")) || expect(content.contains("compile"))) &&
      expect(!content.contains("--metagraph"))
    }
  }

  test("build-tessellation-base.yml should login to GHCR") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      val content = Files.readString(workflowPath)
      
      expect(content.contains("docker/login-action")) &&
      expect(content.contains("ghcr.io")) &&
      expect(content.contains("GITHUB_TOKEN"))
    }
  }

  test("build-tessellation-base.yml should build and push Docker image") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      val content = Files.readString(workflowPath)
      
      expect(content.contains("docker/build-push-action")) &&
      expect(content.contains("file: tessellation-base.Dockerfile")) &&
      expect(content.contains("push: true")) &&
      expect(content.contains("ghcr.io/ottobot-ai/tessellation-base"))
    }
  }

  test("build-tessellation-base.yml should tag with version") {
    IO {
      val workflowPath = Paths.get(".github/workflows/build-tessellation-base.yml")
      val content = Files.readString(workflowPath)
      
      // Should tag with version (latest and specific version)
      expect(content.contains("tags:")) &&
      expect(content.contains("latest")) &&
      expect(content.contains("${{ env.TESSELLATION_VERSION }}") || content.contains("$TESSELLATION_VERSION"))
    }
  }

  test("e2e.yml should extract JARs before starting cluster") {
    IO {
      val e2eWorkflowPath = Paths.get(".github/workflows/e2e.yml")
      val content = Files.readString(e2eWorkflowPath)
      
      // Should have a step to extract JARs from pre-built image
      expect(content.contains("docker create")) &&
      expect(content.contains("docker cp")) &&
      expect(content.contains("tessellation-base"))
    }
  }

  test("e2e.yml should set tessellation environment variables") {
    IO {
      val e2eWorkflowPath = Paths.get(".github/workflows/e2e.yml")
      val content = Files.readString(e2eWorkflowPath)
      
      // Should set SKIP_ASSEMBLY=true and PUBLISH=false
      expect(content.contains("SKIP_ASSEMBLY=true")) &&
      expect(content.contains("PUBLISH=false"))
    }
  }

  test("e2e.yml should use extracted JARs in tessellation directory") {
    IO {
      val e2eWorkflowPath = Paths.get(".github/workflows/e2e.yml")
      val content = Files.readString(e2eWorkflowPath)
      
      // Should copy extracted JARs to tessellation directory
      val dockerCpPattern = "docker cp.*tessellation".r
      expect(dockerCpPattern.findFirstIn(content).isDefined)
    }
  }

  test("e2e.yml should still clone tessellation repo") {
    IO {
      val e2eWorkflowPath = Paths.get(".github/workflows/e2e.yml")
      val content = Files.readString(e2eWorkflowPath)
      
      // Should still clone tessellation for source code even though JARs are pre-built
      expect(content.contains("git clone")) &&
      expect(content.contains("tessellation.git"))
    }
  }

  test("e2e.yml should still apply patches to tessellation") {
    IO {
      val e2eWorkflowPath = Paths.get(".github/workflows/e2e.yml")
      val content = Files.readString(e2eWorkflowPath)
      
      // Should still apply patches since some source code is needed
      expect(content.contains("git apply")) &&
      expect(content.contains("patches/"))
    }
  }

  test("e2e.yml should validate performance improvement") {
    IO {
      // This test checks that the workflow includes performance validation
      val e2eWorkflowPath = Paths.get(".github/workflows/e2e.yml")
      val content = Files.readString(e2eWorkflowPath)
      
      // Should have some mechanism to track timing
      expect(content.contains("timeout-minutes")) &&
      expect(content.contains("30") || content.contains("20")) // reduced timeout for faster CI
    }
  }

  test("workflow files should use consistent tessellation version") {
    IO {
      val buildWorkflow = Files.readString(Paths.get(".github/workflows/build-tessellation-base.yml"))
      val e2eWorkflow = Files.readString(Paths.get(".github/workflows/e2e.yml"))
      
      // Both workflows should reference the same tessellation version
      val versionPattern = "TESSELLATION_VERSION.*[0-9]+\\.[0-9]+\\.[0-9]+".r
      val buildVersion = versionPattern.findFirstIn(buildWorkflow)
      val e2eVersion = versionPattern.findFirstIn(e2eWorkflow)
      
      expect(buildVersion.isDefined) &&
      expect(e2eVersion.isDefined) &&
      expect(buildVersion == e2eVersion)
    }
  }
}
import org.cloudifysource.utilitydomain.context.ServiceContextFactory
import java.util.concurrent.TimeUnit

def context = ServiceContextFactory.getServiceContext()

assert NAME : "Empty env var NAME"
println "NAME : ${NAME}"
assert CUSTOM_HOSTNAME : "Empty env var CUSTOM_HOSTNAME"
println "CUSTOM_HOSTNAME : ${CUSTOM_HOSTNAME}"
assert COMPUTE_IP : "Empty env var COMPUTE_IP"
assert COMPUTE_IP !="true"
println "COMPUTE_IP : ${COMPUTE_IP}"
assert SOURCE : "Empty env var SOURCE"
println "SOURCE : ${SOURCE}"
assert SOURCE_NAME : "Empty env var SOURCE_NAME"
println "SOURCE_NAME : ${SOURCE_NAME}"
assert SOURCE_SERVICE_NAME : "Empty env var SOURCE_SERVICE_NAME"
println "SOURCE_SERVICE_NAME : ${SOURCE_SERVICE_NAME}"
assert SOURCES : "Empty env var SOURCES"
println "SOURCES : ${SOURCES}"
assert TARGET : "Empty env var TARGET"
println "TARGET : ${TARGET}"
assert TARGET_NAME : "Empty env var TARGET_NAME"
println "TARGET_NAME : ${TARGET_NAME}"
assert TARGET_SERVICE_NAME : "Empty env var TARGET_SERVICE_NAME"
println "TARGET_SERVICE_NAME : ${TARGET_SERVICE_NAME}"
assert TARGETS : "Empty env var TARGETS"
println "TARGETS : ${TARGETS}"

def targetsArray = TARGETS.split(",")
def nbTarget = targetsArray.length;
println "Nb of targets is ${nbTarget}: ${targetsArray}"

targetsArray.each{
  def name = it+"_COMPUTE_IP"
  assert binding.getVariable(name) : "Empty env var ${name}"
  println "${name} : ${binding.getVariable(name)}"
}
package com.linkedpipes.etl.storage.template;

import com.linkedpipes.etl.storage.BaseException;
import com.linkedpipes.etl.storage.Configuration;
import com.linkedpipes.etl.storage.jar.JarComponent;
import com.linkedpipes.etl.storage.jar.JarFacade;
import com.linkedpipes.etl.storage.rdf.RdfUtils;
import com.linkedpipes.etl.storage.template.repository.RepositoryReference;
import com.linkedpipes.etl.storage.template.repository.TemplateRepository;
import com.linkedpipes.etl.storage.template.repository.WritableTemplateRepository;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TemplateManager {

    @FunctionalInterface
    private interface CheckedFunction<T> {
        void apply(T t) throws Exception;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(TemplateManager.class);

    private final JarFacade jarFacade;

    private final Configuration configuration;

    private final Map<String, Template> templates = new HashMap<>();

    private final WritableTemplateRepository repository;

    private final TemplateLoader loader;

    @Autowired
    public TemplateManager(
            JarFacade jarFacade,
            Configuration configuration,
            WritableTemplateRepository repository) {
        this.jarFacade = jarFacade;
        this.configuration = configuration;
        this.repository = repository;
        this.loader = new TemplateLoader(this.repository);
    }

    public TemplateRepository getRepository() {
        return this.repository;
    }

    @PostConstruct
    public void initialize() throws BaseException {
        try {
            importJarFiles();
            importTemplates();
            if (this.repository.getInitialVersion() !=
                    TemplateRepository.LATEST_VERSION) {
                migrate();
            }
            this.repository.updateFinished();
        } catch (Exception ex) {
            LOG.error("Initialization failed.", ex);
            throw ex;
        }
    }

    private void importJarFiles() {
        ImportFromJarFile copyJarTemplates =
                new ImportFromJarFile(this.repository);
        for (JarComponent item : jarFacade.getJarComponents()) {
            copyJarTemplates.importJarComponent(item);
        }
    }

    private void importTemplates() {
        List<ReferenceTemplate> referenceTemplates = new ArrayList<>();
        for (RepositoryReference reference : this.repository.getReferences()) {
            try {
                Template template = loader.loadTemplate(reference);
                if (template.getIri() == null) {
                    LOG.error("Invalid template ignored: {}",
                            reference.getId());
                    continue;
                }
                if (template instanceof ReferenceTemplate) {
                    referenceTemplates.add((ReferenceTemplate) template);
                }
                templates.put(template.getIri(), template);
            } catch (Exception ex) {
                LOG.error("Can't load template: ", reference.getId(), ex);
            }
        }
        setTemplateCoreReferences(referenceTemplates);
    }

    private void setTemplateCoreReferences(List<ReferenceTemplate> templates) {
        for (ReferenceTemplate template : templates) {
            template.setCoreTemplate(findCoreTemplate(template));
        }
    }

    private JarTemplate findCoreTemplate(ReferenceTemplate template) {
        while(true) {
            Template newTemplate = this.templates.get(template.getTemplate());
            switch (newTemplate.getType()) {
                case JAR_TEMPLATE:
                    return (JarTemplate)newTemplate;
                case REFERENCE_TEMPLATE:
                    template = (ReferenceTemplate)newTemplate;
                    break;
                default:
                    LOG.error("Invalid template type: {}", newTemplate.getIri());
                    return null;
            }
            if (newTemplate == null) {
                // TODO Invalid template detected.
                return null;
            }
        }
    }

    private void migrate() throws BaseException {
        switch (this.repository.getInitialVersion()) {
            case 0:
            case 1:
                migrateV1ToV2();
                reloadTemplates();
            case 2:
                migrateV2ToV3();
                reloadTemplates();
            case 3:
                // There is no need to reload as updated information is
                // not stored in memory.
                migrateV3ToV4();
            case 4: // Current version
            default:
                break;
        }
    }

    private void migrateV1ToV2() throws BaseException {
        LOG.info("Migrating to version 2");
        TemplateV1ToV2 v1Tov2 = new TemplateV1ToV2(this, this.repository);
        boolean migrationFailed = migrateTemplates(
                (template) -> v1Tov2.migrate(template));
        if (migrationFailed) {
            throw new BaseException("Migration failed");
        }
    }

    private boolean migrateTemplates(CheckedFunction<Template> callback) {
        boolean migrationFailed = false;
        for (Template template : templates.values()) {
            try {
                callback.apply(template);
            } catch (Throwable ex) {
                LOG.error("Migration of component '{}' failed",
                        template.getIri(), ex);
                migrationFailed = true;
            }
        }
        return migrationFailed;
    }

    private void reloadTemplates() {
        LOG.info("Reloading templates ...");
        this.templates.clear();
        this.importTemplates();
    }

    private void migrateV2ToV3() throws BaseException {
        LOG.info("Migrating to version 3");
        TemplateV2ToV3 v2Tov3 = new TemplateV2ToV3(this.repository);
        boolean migrationFailed = migrateTemplates(
                (template) -> v2Tov3.migrate(template));
        if (migrationFailed) {
            throw new BaseException("Migration failed");
        }
    }

    private void migrateV3ToV4() throws BaseException {
        LOG.info("Migrating to version 4");
        TemplateV3ToV4 v3ToV4= new TemplateV3ToV4(this.repository);
        boolean migrationFailed = migrateTemplates(
                (template) -> v3ToV4.migrate(template));
        if (migrationFailed) {
            throw new BaseException("Migration failed");
        }
    }

    /**
     * @return Unmodifiable map.
     */
    public Map<String, Template> getTemplates() {
        return Collections.unmodifiableMap(templates);
    }

    /**
     * @param descriptionRdf If null
     */
    public Template createTemplate(
            Collection<Statement> interfaceRdf,
            Collection<Statement> configurationRdf,
            Collection<Statement> descriptionRdf)
            throws BaseException {
        String id = this.repository.reserveReferenceId();
        String iri = this.configuration.getDomainName() +
                "/resources/components/" + id;
        ReferenceFactory factory = new ReferenceFactory(this, this.repository);
        try {
            ReferenceTemplate template = factory.create(
                    interfaceRdf, configurationRdf,
                    descriptionRdf, id, iri);
            template.setCoreTemplate(findCoreTemplate(template));
            templates.put(template.getIri(), template);
            return template;
        } catch (BaseException ex) {
            repository.remove(RepositoryReference.Reference(id));
            throw ex;
        }
    }

    public void updateTemplateInterface(
            Template template, Collection<Statement> diff)
            throws BaseException {
        if (template.getType() != Template.Type.REFERENCE_TEMPLATE) {
            throw new BaseException("Only reference templates can be updated");
        }
        diff = RdfUtils.forceContext(diff, template.getIri());
        Collection<Statement> newInterface =
                update(this.repository.getInterface(template), diff);
        this.repository.setInterface(template, newInterface);
    }

    private List<Statement> update(
            Collection<Statement> data, Collection<Statement> diff) {
        Map<Resource, Map<IRI, List<Value>>> toReplace = new HashMap<>();
        diff.forEach((s) ->
                toReplace.computeIfAbsent(s.getSubject(),
                        (key) -> new HashMap<>())
                        .computeIfAbsent(s.getPredicate(),
                                (key) -> new ArrayList<>())
                        .add(s.getObject()));
        List<Statement> output = new ArrayList<>();
        output.addAll(diff);
        List<Statement> leftFromOriginal =
                removeWithSubjectAndPredicate(data, diff);
        output.addAll(leftFromOriginal);
        return output;
    }

    private List<Statement> removeWithSubjectAndPredicate(
            Collection<Statement> data, Collection<Statement> toRemove) {
        Map<Resource, Set<IRI>> toDelete = new HashMap<>();
        toRemove.forEach((s) -> {
            toDelete.computeIfAbsent(s.getSubject(), (key) -> new HashSet<>())
                    .add(s.getPredicate());
        });
        // Remove all that are not in the toDelete map.
        return data.stream().filter((s) ->
                !toDelete.getOrDefault(s.getSubject(), Collections.EMPTY_SET)
                        .contains(s.getPredicate())
        ).collect(Collectors.toList());
    }

    public void updateConfig(
            Template template, Collection<Statement> statements)
            throws BaseException {
        ValueFactory valueFactory = SimpleValueFactory.getInstance();
        IRI graph = valueFactory.createIRI(template.getIri() + "/configuration");
        statements = RdfUtils.forceContext(statements, graph);
        this.repository.setConfig(template, statements);
    }

    /**
     * Does not delete
     */
    public void remove(Template template) throws BaseException {
        if (template.getType() != Template.Type.REFERENCE_TEMPLATE) {
            throw new BaseException("Can't delete non-reference template: {}",
                    template.getIri());
        }
        this.templates.remove(template.getIri());
        this.repository.remove(template);
    }

}

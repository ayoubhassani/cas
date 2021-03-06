package org.apereo.cas.web;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apereo.cas.CasProtocolConstants;
import org.apereo.cas.CasViewConstants;
import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationContextValidator;
import org.apereo.cas.authentication.AuthenticationException;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.HttpBasedServiceCredential;
import org.apereo.cas.authentication.MultifactorTriggerSelectionStrategy;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.authentication.principal.WebApplicationService;
import org.apereo.cas.services.MultifactorAuthenticationProvider;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.RegisteredServiceAccessStrategyUtils;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.services.UnauthorizedProxyingException;
import org.apereo.cas.services.UnauthorizedServiceException;
import org.apereo.cas.ticket.AbstractTicketException;
import org.apereo.cas.ticket.AbstractTicketValidationException;
import org.apereo.cas.ticket.InvalidTicketException;
import org.apereo.cas.ticket.ServiceTicket;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.UnsatisfiedAuthenticationContextTicketValidationException;
import org.apereo.cas.ticket.proxy.ProxyHandler;
import org.apereo.cas.validation.Assertion;
import org.apereo.cas.validation.ValidationResponseType;
import org.apereo.cas.validation.ValidationSpecification;
import org.apereo.cas.web.support.ArgumentExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.ServletRequestDataBinder;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Process the /validate , /serviceValidate , and /proxyValidate URL requests.
 * <p>
 * Obtain the Service Ticket and Service information and present them to the CAS
 * validation services. Receive back an Assertion containing the user Principal
 * and (possibly) a chain of Proxy Principals. Store the Assertion in the Model
 * and chain to a View to generate the appropriate response (CAS 1, CAS 2 XML,
 * SAML, ...).
 *
 * @author Scott Battaglia
 * @author Misagh Moayyed
 * @since 3.0.0
 */
public abstract class AbstractServiceValidateController extends AbstractDelegateController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractServiceValidateController.class);
    
    private ValidationSpecification validationSpecification;
    
    private AuthenticationSystemSupport authenticationSystemSupport;
            
    private ServicesManager servicesManager;
    
    private CentralAuthenticationService centralAuthenticationService;

    /** The proxy handler we want to use with the controller. */
    private ProxyHandler proxyHandler;

    /** The view to redirect to on a successful validation. */
    private View successView;

    /** The view to redirect to on a validation failure. */
    private View failureView;

    /** Extracts parameters from Request object. */
    private ArgumentExtractor argumentExtractor;
    
    private MultifactorTriggerSelectionStrategy multifactorTriggerSelectionStrategy;
        
    private AuthenticationContextValidator authenticationContextValidator;
    
    private View jsonView;

    private String authnContextAttribute;
    
    /**
     * Instantiates a new Service validate controller.
     */
    public AbstractServiceValidateController() {}

    /**
     * Overrideable method to determine which credentials to use to grant a
     * proxy granting ticket. Default is to use the pgtUrl.
     *
     * @param service the webapp service requesting proxy
     * @param request the HttpServletRequest object.
     * @return the credentials or null if there was an error or no credentials
     * provided.
     */
    protected Credential getServiceCredentialsFromRequest(final WebApplicationService service, final HttpServletRequest request) {
        final String pgtUrl = request.getParameter(CasProtocolConstants.PARAMETER_PROXY_CALLBACK_URL);
        if (StringUtils.hasText(pgtUrl)) {
            try {
                final RegisteredService registeredService = this.servicesManager.findServiceBy(service);
                verifyRegisteredServiceProperties(registeredService, service);
                return new HttpBasedServiceCredential(new URL(pgtUrl), registeredService);
            } catch (final Exception e) {
                LOGGER.error("Error constructing [{}]", CasProtocolConstants.PARAMETER_PROXY_CALLBACK_URL, e);
            }
        }
        return null;
    }

    /**
     * Validate authentication context pair.
     *
     * @param assertion the assertion
     * @param request   the request
     * @return the pair
     */
    protected Pair<Boolean, Optional<MultifactorAuthenticationProvider>> validateAuthenticationContext(final Assertion assertion,
                                                                                                       final HttpServletRequest request) {
        // find the RegisteredService for this Assertion
        LOGGER.debug("Locating the primary authentication associated with this service request [{}]", assertion.getService());
        final RegisteredService service = this.servicesManager.findServiceBy(assertion.getService());
        RegisteredServiceAccessStrategyUtils.ensureServiceAccessIsAllowed(assertion.getService(), service);

        // resolve MFA auth context for this request
        final Map<String, MultifactorAuthenticationProvider> providers =
                this.applicationContext.getBeansOfType(MultifactorAuthenticationProvider.class, false, true);
        final Authentication authentication = assertion.getPrimaryAuthentication();
        final Optional<String> requestedContext = this.multifactorTriggerSelectionStrategy.resolve(providers.values(), request,
                service, authentication.getPrincipal());

        // no MFA auth context found
        if (!requestedContext.isPresent()) {
            LOGGER.debug("No particular authentication context is required for this request");
            return Pair.of(Boolean.TRUE, Optional.empty());
        }

        // validate the requested strategy
        return this.authenticationContextValidator.validate(authentication, requestedContext.get(), service);
    }

    /**
     * Inits the binder with the required fields. {@code renew} is required.
     *
     * @param request the request
     * @param binder the binder
     */
    protected void initBinder(final HttpServletRequest request, final ServletRequestDataBinder binder) {
        binder.setRequiredFields(CasProtocolConstants.PARAMETER_RENEW);
    }

    /**
     * Handle proxy granting ticket delivery.
     *
     * @param serviceTicketId the service ticket id
     * @param credential the service credential
     * @return the ticket granting ticket
     */
    private TicketGrantingTicket handleProxyGrantingTicketDelivery(final String serviceTicketId, final Credential credential)
        throws AuthenticationException, AbstractTicketException {
        final ServiceTicket serviceTicket = this.centralAuthenticationService.getTicket(serviceTicketId, ServiceTicket.class);
        final AuthenticationResult authenticationResult =
                this.authenticationSystemSupport.handleAndFinalizeSingleAuthenticationTransaction(serviceTicket.getService(), credential);
        final TicketGrantingTicket proxyGrantingTicketId = this.centralAuthenticationService.createProxyGrantingTicket(serviceTicketId, authenticationResult);
        LOGGER.debug("Generated proxy-granting ticket [[{}]] off of service ticket [{}] and credential [{}]",
                proxyGrantingTicketId.getId(), serviceTicketId, credential);

        return proxyGrantingTicketId;
    }

    @Override
    protected ModelAndView handleRequestInternal(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        final WebApplicationService service = this.argumentExtractor.extractService(request);
        final String serviceTicketId = service != null ? service.getArtifactId() : null;

        if (service == null || serviceTicketId == null) {
            LOGGER.debug("Could not identify service and/or service ticket for service: [{}]", service);
            return generateErrorView(CasProtocolConstants.ERROR_CODE_INVALID_REQUEST, null, request, service);
        }

        try {
            return handleTicketValidation(request, service, serviceTicketId);
        } catch (final AbstractTicketValidationException e) {
            final String code = e.getCode();
            return generateErrorView(code, new Object[]{serviceTicketId, e.getOriginalService().getId(), service.getId()}, request, service);
        } catch (final AbstractTicketException e) {
            return generateErrorView(e.getCode(), 
                new Object[] {serviceTicketId}, request, service);
        } catch (final UnauthorizedProxyingException e) {
            return generateErrorView(CasProtocolConstants.ERROR_CODE_UNAUTHORIZED_SERVICE_PROXY, new Object[]{service.getId()}, request, service);
        } catch (final UnauthorizedServiceException e) {
            return generateErrorView(CasProtocolConstants.ERROR_CODE_UNAUTHORIZED_SERVICE, null, request, service);
        }
    }

    /**
     * Handle ticket validation model and view.
     *
     * @param request         the request
     * @param service         the service
     * @param serviceTicketId the service ticket id
     * @return the model and view
     */
    protected ModelAndView handleTicketValidation(final HttpServletRequest request, final WebApplicationService service, final String serviceTicketId) {
        TicketGrantingTicket proxyGrantingTicketId = null;
        final Credential serviceCredential = getServiceCredentialsFromRequest(service, request);
        if (serviceCredential != null) {
            try {
                proxyGrantingTicketId = handleProxyGrantingTicketDelivery(serviceTicketId, serviceCredential);
            } catch (final AuthenticationException e) {
                LOGGER.warn("Failed to authenticate service credential [{}]", serviceCredential);
                return generateErrorView(CasProtocolConstants.ERROR_CODE_INVALID_PROXY_CALLBACK, new Object[]{serviceCredential.getId()}, request, service);
            } catch (final InvalidTicketException e) {
                LOGGER.error("Failed to create proxy granting ticket due to an invalid ticket for [{}]", serviceCredential, e);
                return generateErrorView(e.getCode(), new Object[]{serviceTicketId}, request, service);
            } catch (final AbstractTicketException e) {
                LOGGER.error("Failed to create proxy granting ticket for [{}]", serviceCredential, e);
                return generateErrorView(e.getCode(), new Object[]{serviceCredential.getId()}, request, service);
            }
        }

        final Assertion assertion = this.centralAuthenticationService.validateServiceTicket(serviceTicketId, service);
        if (!validateAssertion(request, serviceTicketId, assertion)) {
            return generateErrorView(CasProtocolConstants.ERROR_CODE_INVALID_TICKET, new Object[]{serviceTicketId}, request, service);
        }

        final Pair<Boolean, Optional<MultifactorAuthenticationProvider>> ctxResult = validateAuthenticationContext(assertion, request);
        if (!ctxResult.getKey()) {
            throw new UnsatisfiedAuthenticationContextTicketValidationException(assertion.getService());
        }

        String proxyIou = null;
        if (serviceCredential != null && this.proxyHandler.canHandle(serviceCredential)) {
            proxyIou = handleProxyIouDelivery(serviceCredential, proxyGrantingTicketId);
            if (StringUtils.isEmpty(proxyIou)) {
                return generateErrorView(CasProtocolConstants.ERROR_CODE_INVALID_PROXY_CALLBACK, new Object[]{serviceCredential.getId()}, request, service);
            }
        } else {
            LOGGER.debug("No service credentials specified, and/or the proxy handler [{}] cannot handle credentials", 
                    this.proxyHandler.getClass().getSimpleName());
        }

        onSuccessfulValidation(serviceTicketId, assertion);
        LOGGER.debug("Successfully validated service ticket [{}] for service [{}]", serviceTicketId, service.getId());
        return generateSuccessView(assertion, proxyIou, service, request, ctxResult.getValue(), proxyGrantingTicketId);
    }

    private String handleProxyIouDelivery(final Credential serviceCredential, final TicketGrantingTicket proxyGrantingTicketId) {
        return this.proxyHandler.handle(serviceCredential, proxyGrantingTicketId);
    }
    
    /**
     * Validate assertion.
     *
     * @param request the request
     * @param serviceTicketId the service ticket id
     * @param assertion the assertion
     * @return true/false
     */
    private boolean validateAssertion(final HttpServletRequest request, final String serviceTicketId, final Assertion assertion) {
        this.validationSpecification.reset();
        final ServletRequestDataBinder binder = new ServletRequestDataBinder(this.validationSpecification, "validationSpecification");
        initBinder(request, binder);
        binder.bind(request);
        
        if (!this.validationSpecification.isSatisfiedBy(assertion, request)) {
            LOGGER.warn("Service ticket [{}] does not satisfy validation specification.", serviceTicketId);
            return false;
        }
        return true;
    }

    /**
     * Triggered on successful validation events. Extensions are to
     * use this as hook to plug in behavior.
     *
     * @param serviceTicketId the service ticket id
     * @param assertion the assertion
     */
    protected void onSuccessfulValidation(final String serviceTicketId, final Assertion assertion) {
        // template method with nothing to do.
    }

    /**
     * Generate error view.
     *
     * @param code the code
     * @param args the args
     * @param request the request
     * @return the model and view
     */
    private ModelAndView generateErrorView(final String code,
                                           final Object[] args,
                                           final HttpServletRequest request,
                                           final WebApplicationService service) {

        final ModelAndView modelAndView = getModelAndView(request, false, service);
        final String convertedDescription = this.applicationContext.getMessage(code, args, code, request.getLocale());
        modelAndView.addObject(CasViewConstants.MODEL_ATTRIBUTE_NAME_ERROR_CODE, StringEscapeUtils.escapeHtml4(code));
        modelAndView.addObject(CasViewConstants.MODEL_ATTRIBUTE_NAME_ERROR_DESCRIPTION, StringEscapeUtils.escapeHtml4(convertedDescription));

        return modelAndView;
    }

    private ModelAndView getModelAndView(final HttpServletRequest request, final boolean isSuccess, final WebApplicationService service) {

        ValidationResponseType type = service != null ? service.getFormat() : ValidationResponseType.XML;
        final String format = request.getParameter(CasProtocolConstants.PARAMETER_FORMAT);
        if (!StringUtils.isEmpty(format)) {
            try {
                type = ValidationResponseType.valueOf(format.toUpperCase());
            } catch (final Exception e) {
                LOGGER.warn(e.getMessage(), e);
            }
        }
        
        if (type == ValidationResponseType.JSON) {
            return new ModelAndView(this.jsonView);
        }
        
        return new ModelAndView(isSuccess ? this.successView : this.failureView);
    }

    /**
     * Generate the success view. The result will contain the assertion and the proxy iou.
     *
     * @param assertion           the assertion
     * @param proxyIou            the proxy iou
     * @param service             the validated service
     * @param contextProvider     the context provider
     * @param proxyGrantingTicket the proxy granting ticket
     * @return the model and view, pointed to the view name set by
     */
    private ModelAndView generateSuccessView(final Assertion assertion, 
                                             final String proxyIou,
                                             final WebApplicationService service,
                                             final HttpServletRequest request,
                                             final Optional<MultifactorAuthenticationProvider> contextProvider,
                                             final TicketGrantingTicket proxyGrantingTicket) {

        final ModelAndView modelAndView = getModelAndView(request, true, service);

        modelAndView.addObject(CasViewConstants.MODEL_ATTRIBUTE_NAME_ASSERTION, assertion);
        modelAndView.addObject(CasViewConstants.MODEL_ATTRIBUTE_NAME_SERVICE, service);
        
        if (StringUtils.hasText(proxyIou)) {
            modelAndView.addObject(CasViewConstants.MODEL_ATTRIBUTE_NAME_PROXY_GRANTING_TICKET_IOU, proxyIou);
        }
        if (proxyGrantingTicket != null) {
            modelAndView.addObject(CasViewConstants.MODEL_ATTRIBUTE_NAME_PROXY_GRANTING_TICKET, proxyGrantingTicket.getId());
        }

        contextProvider.ifPresent(provider -> modelAndView.addObject(this.authnContextAttribute, provider.getId()));

        final Map<String, ?> augmentedModelObjects = augmentSuccessViewModelObjects(assertion);
        if (augmentedModelObjects != null) {
            modelAndView.addAllObjects(augmentedModelObjects);
        }
        return modelAndView;
    }

    /**
     * Augment success view model objects. Provides
     * a way for extension of this controller to dynamically
     * populate the model object with attributes
     * that describe a custom nature of the validation protocol.
     *
     * @param assertion the assertion
     * @return map of objects each keyed to a name
     */
    protected Map<String, ?> augmentSuccessViewModelObjects(final Assertion assertion) {
        return Collections.emptyMap();  
    }

    @Override
    public boolean canHandle(final HttpServletRequest request, final HttpServletResponse response) {
        return true;
    }

    /**
     * @param centralAuthenticationService The centralAuthenticationService to
     * set.
     */
    public void setCentralAuthenticationService(final CentralAuthenticationService centralAuthenticationService) {
        this.centralAuthenticationService = centralAuthenticationService;
    }
    
    public void setArgumentExtractor(final ArgumentExtractor argumentExtractor) {
        this.argumentExtractor = argumentExtractor;
    }

    public void setMultifactorTriggerSelectionStrategy(final MultifactorTriggerSelectionStrategy strategy) {
        this.multifactorTriggerSelectionStrategy = strategy;
    }

    /**
     * @param validationSpecificationClass The authenticationSpecificationClass
     * to set.
     */
    public void setValidationSpecification(final ValidationSpecification validationSpecificationClass) {
        this.validationSpecification = validationSpecificationClass;
    }

    /**
     * @param failureView The failureView to set.
     */
    public void setFailureView(final View failureView) {
        this.failureView = failureView;
    }

    /**
     * Return the failureView.
     * @return the failureView
     */
    public View getFailureView() {
        return this.failureView;
    }

    /**
     * @param successView The successView to set.
     */
    public void setSuccessView(final View successView) {
        this.successView = successView;
    }

    /**
     * Return the successView.
     * @return the successView
     */
    public View getSuccessView() {
        return this.successView;
    }
    
    /**
     * @param proxyHandler The proxyHandler to set.
     */
    public void setProxyHandler(final ProxyHandler proxyHandler) {
        this.proxyHandler = proxyHandler;
    }

    /**
     * Sets the services manager.
     *
     * @param servicesManager the new services manager
     */
    public void setServicesManager(final ServicesManager servicesManager) {
        this.servicesManager = servicesManager;
    }

    /**
     * Ensure that the service is found and enabled in the service registry.
     * @param registeredService the located entry in the registry
     * @param service authenticating service
     * @throws UnauthorizedServiceException if service is determined to be unauthorized
     */
    private void verifyRegisteredServiceProperties(final RegisteredService registeredService, final Service service) {
        if (registeredService == null) {
            final String msg = String.format("Service [%s] is not found in service registry.", service.getId());
            LOGGER.warn(msg);
            throw new UnauthorizedServiceException(UnauthorizedServiceException.CODE_UNAUTHZ_SERVICE, msg);
        }
        if (!registeredService.getAccessStrategy().isServiceAccessAllowed()) {
            final String msg = String.format("ServiceManagement: Unauthorized Service Access. "
                    + "Service [%s] is not enabled in service registry.", service.getId());

            LOGGER.warn(msg);
            throw new UnauthorizedServiceException(UnauthorizedServiceException.CODE_UNAUTHZ_SERVICE, msg);
        }
    }

    public void setAuthenticationSystemSupport(final AuthenticationSystemSupport authenticationSystemSupport) {
        this.authenticationSystemSupport = authenticationSystemSupport;
    }
    
    public void setAuthenticationContextValidator(final AuthenticationContextValidator authenticationContextValidator) {
        this.authenticationContextValidator = authenticationContextValidator;
    }

    public void setJsonView(final View jsonView) {
        this.jsonView = jsonView;
    }

    public void setAuthnContextAttribute(final String authnContextAttribute) {
        this.authnContextAttribute = authnContextAttribute;
    }
}

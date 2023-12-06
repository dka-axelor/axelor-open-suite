/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2023 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.base.service;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.AddressTemplate;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PartnerAddress;
import com.axelor.apps.base.db.PickListEntry;
import com.axelor.apps.base.db.Street;
import com.axelor.apps.base.db.repo.AddressRepository;
import com.axelor.apps.base.db.repo.AddressTemplateRepository;
import com.axelor.apps.base.db.repo.CityRepository;
import com.axelor.apps.base.db.repo.StreetRepository;
import com.axelor.apps.base.exceptions.BaseExceptionMessage;
import com.axelor.apps.base.service.administration.AbstractBatch;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.common.StringUtils;
import com.axelor.common.csv.CSVFile;
import com.axelor.db.EntityHelper;
import com.axelor.db.JPA;
import com.axelor.db.Query;
import com.axelor.db.mapper.Mapper;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.Context;
import com.axelor.text.GroovyTemplates;
import com.axelor.text.StringTemplates;
import com.axelor.text.Templates;
import com.axelor.utils.helpers.address.AddressHelper;
import com.google.api.client.util.Maps;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wslite.json.JSONException;

@Singleton
public class AddressServiceImpl implements AddressService {
  @Inject protected AddressRepository addressRepo;
  @Inject protected AddressHelper ads;
  @Inject protected MapService mapService;
  @Inject protected CityRepository cityRepository;
  @Inject protected StreetRepository streetRepository;
  @Inject protected AppBaseService appBaseService;

  protected static final Set<Function<Long, Boolean>> checkUsedFuncs = new LinkedHashSet<>();

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final char TEMPLATE_DELIMITER = '$';
  private static final Pattern ZIP_CODE_PATTERN =
      Pattern.compile(
          "\\d{4} [A-Z]{2} |\\d{4,6}|\\d{3}-\\d{4}|[A-Z]{1,2}[0-9][A-Z0-9]? [0-9][A-Z]{1}[A-Z0-9]?");

  static {
    registerCheckUsedFunc(AddressServiceImpl::checkAddressUsedBase);
  }

  @Override
  public boolean check(String wsdlUrl) {
    return ads.doCanSearch(wsdlUrl);
  }

  @Override
  public Map<String, Object> validate(String wsdlUrl, String search) {
    return ads.doSearch(wsdlUrl, search);
  }

  @Override
  public com.qas.web_2005_02.Address select(String wsdlUrl, String moniker) {
    return ads.doGetAddress(wsdlUrl, moniker);
  }

  @Override
  public int export(String path) throws IOException {
    List<Address> addresses = addressRepo.all().filter("self.certifiedOk IS FALSE").fetch();

    File tempFile = new File(path);
    CSVFile csvFormat = CSVFile.DEFAULT.withDelimiter('|').withFirstRecordAsHeader();
    CSVPrinter printer = csvFormat.write(tempFile);

    List<String> header = new ArrayList<>();
    header.add("Id");
    header.add("AddressL1");
    header.add("AddressL2");
    header.add("AddressL3");
    header.add("AddressL4");
    header.add("AddressL5");
    header.add("AddressL6");
    header.add("CodeINSEE");

    printer.printRecord(header);
    List<String> items = new ArrayList<>();
    for (Address a : addresses) {

      items.add(a.getId() != null ? a.getId().toString() : "");
      items.add(a.getRoom() != null ? a.getRoom() : "");
      items.add(a.getFloor() != null ? a.getFloor() : "");
      items.add(a.getStreetName() != null ? a.getStreetName() : "");
      items.add(a.getPostBox() != null ? a.getPostBox() : "");
      items.add(a.getInseeCode() != null ? a.getInseeCode() : "");

      printer.printRecord(items);
      items.clear();
    }
    printer.close();
    LOG.info("{} exported", path);

    return addresses.size();
  }

  @Override
  public Address createAddress(
      String addressL2,
      String addressL3,
      String addressL4,
      String addressL5,
      String addressL6,
      Country addressL7Country) {

    Address address = new Address();
    address.setRoom(addressL2);
    address.setFloor(addressL3);
    address.setStreetName(addressL4);
    address.setPostBox(addressL5);
    address.setCountry(addressL7Country);

    return address;
  }

  @Override
  public Address getAddress(
      String addressL2,
      String addressL3,
      String addressL4,
      String addressL5,
      String addressL6,
      Country addressL7Country) {

    return addressRepo
        .all()
        .filter(
            "self.addressL2 = ?1 AND self.addressL3 = ?2 AND self.addressL4 = ?3 "
                + "AND self.addressL5 = ?4 AND self.addressL6 = ?5 AND self.addressL7Country = ?6",
            addressL2,
            addressL3,
            addressL4,
            addressL5,
            addressL6,
            addressL7Country)
        .fetchOne();
  }

  @Override
  public boolean checkAddressUsed(Long addressId) {
    LOG.debug("Address Id to be checked = {}", addressId);
    return checkUsedFuncs.stream().anyMatch(checkUsedFunc -> checkUsedFunc.apply(addressId));
  }

  protected static void registerCheckUsedFunc(Function<Long, Boolean> checkUsedFunc) {
    checkUsedFuncs.add(checkUsedFunc);
  }

  private static boolean checkAddressUsedBase(Long addressId) {
    return JPA.all(PartnerAddress.class).filter("self.address.id = ?1", addressId).fetchOne()
            != null
        || JPA.all(Partner.class).filter("self.mainAddress.id = ?1", addressId).fetchOne() != null
        || JPA.all(PickListEntry.class).filter("self.address.id = ?1", addressId).fetchOne()
            != null;
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public Optional<Pair<BigDecimal, BigDecimal>> getOrUpdateLatLong(Address address)
      throws AxelorException, JSONException {
    Preconditions.checkNotNull(address, I18n.get(BaseExceptionMessage.ADDRESS_CANNOT_BE_NULL));
    Optional<Pair<BigDecimal, BigDecimal>> latLong = getLatLong(address);

    if (latLong.isPresent()) {
      return latLong;
    }

    return updateLatLong(address);
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public Optional<Pair<BigDecimal, BigDecimal>> updateLatLong(Address address)
      throws AxelorException, JSONException {
    Preconditions.checkNotNull(address, I18n.get(BaseExceptionMessage.ADDRESS_CANNOT_BE_NULL));

    if (mapService.isConfigured() && StringUtils.notBlank(address.getFullName())) {
      Map<String, Object> result = mapService.getMap(address.getFullName());
      if (result == null) {
        address.setIsValidLatLong(false);
        return Optional.empty();
      }
      address.setIsValidLatLong(true);
      BigDecimal latitude = (BigDecimal) result.get("latitude");
      BigDecimal longitude = (BigDecimal) result.get("longitude");
      setLatLong(address, Pair.of(latitude, longitude));
    }

    return getLatLong(address);
  }

  @Override
  @Transactional
  public void resetLatLong(Address address) {
    Preconditions.checkNotNull(address, I18n.get(BaseExceptionMessage.ADDRESS_CANNOT_BE_NULL));
    setLatLong(address, Pair.of(null, null));
  }

  protected void setLatLong(Address address, Pair<BigDecimal, BigDecimal> latLong) {
    address.setLatit(latLong.getLeft());
    address.setLongit(latLong.getRight());
  }

  protected Optional<Pair<BigDecimal, BigDecimal>> getLatLong(Address address) {
    if (address.getLatit() != null && address.getLongit() != null) {
      return Optional.of(Pair.of(address.getLatit(), address.getLongit()));
    }

    return Optional.empty();
  }

  @Override
  public String computeFullName(Address address) {

    String l2 = address.getRoom();
    String l3 = address.getFloor();
    String l4 = address.getStreetName();
    String l5 = address.getPostBox();

    return (!Strings.isNullOrEmpty(l2) ? l2 : "")
        + (!Strings.isNullOrEmpty(l3) ? " " + l3 : "")
        + (!Strings.isNullOrEmpty(l4) ? " " + l4 : "")
        + (!Strings.isNullOrEmpty(l5) ? " " + l5 : "");
  }

  @Override
  public String computeAddressStr(Address address) {
    if (address == null || address.getCountry() == null) {
      StringBuilder addressString = new StringBuilder();
      if (address == null) {
        return "";
      }

      if (StringUtils.notBlank(address.getRoom())) {
        addressString.append(address.getRoom()).append(System.lineSeparator());
      }
      if (StringUtils.notBlank(address.getFloor())) {
        addressString.append(address.getFloor()).append(System.lineSeparator());
      }
      if (StringUtils.notBlank(address.getStreetName())) {
        addressString.append(address.getStreetName()).append(System.lineSeparator());
      }
      if (StringUtils.notBlank(address.getPostBox())) {
        addressString.append(address.getPostBox()).append(System.lineSeparator());
      }

      if (address.getCountry() != null) {
        addressString.append(System.lineSeparator()).append(address.getCountry().getName());
      }

      return addressString.toString();
    }

    return address.getFormattedFullName();
  }

  @Override
  public void autocompleteAddress(Address address) {
    String zip = address.getZip();
    if (zip == null) {
      return;
    }
    Country country = address.getCountry();

    City city = address.getCity();
    if (city == null) {
      List<City> cities = cityRepository.findByZipAndCountry(zip, country).fetch();
      city = cities.size() == 1 ? cities.get(0) : null;
      address.setCity(city);
    }

    if (appBaseService.getAppBase().getStoreStreets()) {
      List<Street> streets =
          streetRepository.all().filter("self.city = :city").bind("city", city).fetch();
      if (streets.size() == 1) {
        Street street = streets.get(0);
        address.setStreet(street);
        String name = street.getName();
        String num = address.getBuildingNumber();
        address.setStreetName(num != null ? num + " " + name : name);
      } else {
        address.setStreet(null);
        address.setStreetName(null);
      }
    }
  }

  @Override
  public String getZipCode(Address address) {
    //    if (address.getAddressL6() == null) {
    //      return null;
    //    }
    //
    //    Matcher matcher = ZIP_CODE_PATTERN.matcher(address.getAddressL6());
    //    if (matcher.find()) {
    //      return matcher.group();
    //    }
    return "null";
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void setFormattedFullName(Address address) {
    AddressTemplate addressTemplate = address.getCountry().getAddressTemplate();
    String content = addressTemplate.getTemplateStr();

    try {
      Templates templates;
      if (addressTemplate.getEngineSelect() == AddressTemplateRepository.GROOVY_TEMPLATE) {
        templates = Beans.get(GroovyTemplates.class);
      } else {
        templates = new StringTemplates(TEMPLATE_DELIMITER, TEMPLATE_DELIMITER);
      }

      Map<String, Object> templatesContext = Maps.newHashMap();

      Class<?> klass = EntityHelper.getEntityClass(address);
      Context context = new Context(Mapper.toMap(address), klass);

      templatesContext.put(klass.getSimpleName(), context.asType(klass));

      String fullFormattedString = templates.fromText(content).make(templatesContext).render();
      address.setFormattedFullName(fullFormattedString);
    } catch (Exception e) {

    }
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public int computeFormattedAddressForCountries(List<Long> countryIds) {
    int totalAddressFormatted = 0;

    Query<Address> query = addressRepo.all().filter("self.addressL7Country.id in ?1", countryIds);

    try {
      int offset = 0;
      int countExceptions = 0;
      List<Address> addressList = query.fetch(AbstractBatch.FETCH_LIMIT, offset);

      while (!addressList.isEmpty()) {

        totalAddressFormatted = totalAddressFormatted + addressList.size();
        generateFormattedAddressForAddress(addressList);

        JPA.clear();

        offset = addressList.size();
        addressList = query.fetch(AbstractBatch.FETCH_LIMIT, offset);
      }

    } catch (Exception e) {

    }

    return totalAddressFormatted;
  }

  private void generateFormattedAddressForAddress(List<Address> addressList) {
    addressList.forEach(this::setFormattedFullName);
  }
}

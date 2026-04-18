/*
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
var showControllersOnly = false;
var seriesFilter = "";
var filtersOnlySampleSeries = true;

/*
 * Add header in statistics table to group metrics by category
 * format
 *
 */
function summaryTableHeader(header) {
    var newRow = header.insertRow(-1);
    newRow.className = "tablesorter-no-sort";
    var cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 1;
    cell.innerHTML = "Requests";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 3;
    cell.innerHTML = "Executions";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 7;
    cell.innerHTML = "Response Times (ms)";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 1;
    cell.innerHTML = "Throughput";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 2;
    cell.innerHTML = "Network (KB/sec)";
    newRow.appendChild(cell);
}

/*
 * Populates the table identified by id parameter with the specified data and
 * format
 *
 */
function createTable(table, info, formatter, defaultSorts, seriesIndex, headerCreator) {
    var tableRef = table[0];

    // Create header and populate it with data.titles array
    var header = tableRef.createTHead();

    // Call callback is available
    if(headerCreator) {
        headerCreator(header);
    }

    var newRow = header.insertRow(-1);
    for (var index = 0; index < info.titles.length; index++) {
        var cell = document.createElement('th');
        cell.innerHTML = info.titles[index];
        newRow.appendChild(cell);
    }

    var tBody;

    // Create overall body if defined
    if(info.overall){
        tBody = document.createElement('tbody');
        tBody.className = "tablesorter-no-sort";
        tableRef.appendChild(tBody);
        var newRow = tBody.insertRow(-1);
        var data = info.overall.data;
        for(var index=0;index < data.length; index++){
            var cell = newRow.insertCell(-1);
            cell.innerHTML = formatter ? formatter(index, data[index]): data[index];
        }
    }

    // Create regular body
    tBody = document.createElement('tbody');
    tableRef.appendChild(tBody);

    var regexp;
    if(seriesFilter) {
        regexp = new RegExp(seriesFilter, 'i');
    }
    // Populate body with data.items array
    for(var index=0; index < info.items.length; index++){
        var item = info.items[index];
        if((!regexp || filtersOnlySampleSeries && !info.supportsControllersDiscrimination || regexp.test(item.data[seriesIndex]))
                &&
                (!showControllersOnly || !info.supportsControllersDiscrimination || item.isController)){
            if(item.data.length > 0) {
                var newRow = tBody.insertRow(-1);
                for(var col=0; col < item.data.length; col++){
                    var cell = newRow.insertCell(-1);
                    cell.innerHTML = formatter ? formatter(col, item.data[col]) : item.data[col];
                }
            }
        }
    }

    // Add support of columns sort
    table.tablesorter({sortList : defaultSorts});
}

$(document).ready(function() {

    // Customize table sorter default options
    $.extend( $.tablesorter.defaults, {
        theme: 'blue',
        cssInfoBlock: "tablesorter-no-sort",
        widthFixed: true,
        widgets: ['zebra']
    });

    var data = {"OkPercent": 99.492, "KoPercent": 0.508};
    var dataset = [
        {
            "label" : "FAIL",
            "data" : data.KoPercent,
            "color" : "#FF6347"
        },
        {
            "label" : "PASS",
            "data" : data.OkPercent,
            "color" : "#9ACD32"
        }];
    $.plot($("#flot-requests-summary"), dataset, {
        series : {
            pie : {
                show : true,
                radius : 1,
                label : {
                    show : true,
                    radius : 3 / 4,
                    formatter : function(label, series) {
                        return '<div style="font-size:8pt;text-align:center;padding:2px;color:white;">'
                            + label
                            + '<br/>'
                            + Math.round10(series.percent, -2)
                            + '%</div>';
                    },
                    background : {
                        opacity : 0.5,
                        color : '#000'
                    }
                }
            }
        },
        legend : {
            show : true
        }
    });

    // Creates APDEX table
    createTable($("#apdexTable"), {"supportsControllersDiscrimination": true, "overall": {"data": [0.945145, 500, 1500, "Total"], "isController": false}, "titles": ["Apdex", "T (Toleration threshold)", "F (Frustration threshold)", "Label"], "items": [{"data": [0.9451168730220421, 500, 1500, "GET Room Messages 15min"], "isController": false}, {"data": [0.9435719278049582, 500, 1500, "GET User History Repeated"], "isController": false}, {"data": [0.9477570508549855, 500, 1500, "WS Open Connection"], "isController": false}, {"data": [0.9451348767612952, 500, 1500, "GET User Messages 15min"], "isController": false}, {"data": [0.9460700022187708, 500, 1500, "GET User Messages 5min"], "isController": false}, {"data": [0.9453654860587792, 500, 1500, "WS JOIN"], "isController": false}, {"data": [0.9434843400447427, 500, 1500, "GET User Rooms"], "isController": false}, {"data": [0.9472826687285982, 500, 1500, "GET Active Users"], "isController": false}, {"data": [0.9449440808122456, 500, 1500, "WS TEXT"], "isController": false}, {"data": [0.9441456427317253, 500, 1500, "GET Room Messages 5min"], "isController": false}, {"data": [0.9462517680339463, 500, 1500, "GET Room History Repeated"], "isController": false}]}, function(index, item){
        switch(index){
            case 0:
                item = item.toFixed(3);
                break;
            case 1:
            case 2:
                item = formatDuration(item);
                break;
        }
        return item;
    }, [[0, 0]], 3);

    // Create statistics table
    createTable($("#statisticsTable"), {"supportsControllersDiscrimination": true, "overall": {"data": ["Total", 300000, 1524, 0.508, 297.18642000000057, 252, 692, 265.0, 276.0, 582.0, 676.0, 166.61584883277266, 1253.372499490954, 43.94331603860822], "isController": false}, "titles": ["Label", "#Samples", "FAIL", "Error %", "Average", "Min", "Max", "Median", "90th pct", "95th pct", "99th pct", "Transactions/s", "Received", "Sent"], "items": [{"data": ["GET Room Messages 15min", 36022, 185, 0.5135750374770973, 297.1282549553038, 252, 691, 265.5, 572.0, 582.0, 676.0, 20.00612035551379, 187.399157276128, 5.26998011266286], "isController": false}, {"data": ["GET User History Repeated", 24323, 136, 0.5591415532623443, 297.816346667764, 252, 692, 265.0, 573.0, 582.0, 674.0, 13.510556041895333, 126.29191862754791, 3.5817352222912233], "isController": false}, {"data": ["WS Open Connection", 9006, 44, 0.4885631801021541, 295.52753719742225, 252, 691, 265.0, 276.0, 581.0, 673.9300000000003, 5.002563494395585, 0.9285056593027072, 1.322097976103122], "isController": false}, {"data": ["GET User Messages 15min", 24059, 126, 0.523712540005819, 297.0315890103484, 252, 692, 265.0, 572.0, 581.0, 675.0, 13.366653203157684, 125.05563213900481, 3.5258423197510234], "isController": false}, {"data": ["GET User Messages 5min", 36056, 176, 0.48812957621477704, 296.7591524295524, 252, 692, 265.0, 276.0, 581.0, 677.0, 20.029786919642532, 186.59831427724362, 5.282209381018699], "isController": false}, {"data": ["WS JOIN", 11943, 62, 0.5191325462614084, 297.05635100058646, 252, 692, 266.0, 276.0, 582.0, 676.5599999999995, 6.63607283177447, 1.2289584472406372, 1.7497886333637827], "isController": false}, {"data": ["GET User Rooms", 17880, 99, 0.5536912751677853, 298.00167785235, 252, 691, 265.0, 573.0, 582.0, 675.1899999999987, 9.931313966160213, 92.93595328324908, 2.6192070112410475], "isController": false}, {"data": ["GET Active Users", 18106, 91, 0.5025958245885341, 295.92256710482644, 252, 691, 265.0, 276.0, 581.0, 675.0, 10.058721243534832, 93.62956363921712, 2.657317365023083], "isController": false}, {"data": ["WS TEXT", 38806, 189, 0.4870380868937793, 297.51502344998437, 252, 691, 265.0, 572.0, 582.0, 677.0, 21.55341671586714, 4.002086128341304, 5.666123825610664], "isController": false}, {"data": ["GET Room Messages 5min", 54105, 270, 0.4990296645411699, 297.9293410960164, 252, 692, 265.0, 572.0, 582.0, 677.9900000000016, 30.05519423526876, 281.33794762859486, 7.932509709576166], "isController": false}, {"data": ["GET Room History Repeated", 29694, 146, 0.4916818212433488, 296.4373274062117, 252, 692, 265.0, 276.0, 581.0, 673.0, 16.494953024324694, 154.19473066374493, 4.344288802531349], "isController": false}]}, function(index, item){
        switch(index){
            // Errors pct
            case 3:
                item = item.toFixed(2) + '%';
                break;
            // Mean
            case 4:
            // Mean
            case 7:
            // Median
            case 8:
            // Percentile 1
            case 9:
            // Percentile 2
            case 10:
            // Percentile 3
            case 11:
            // Throughput
            case 12:
            // Kbytes/s
            case 13:
            // Sent Kbytes/s
                item = item.toFixed(2);
                break;
        }
        return item;
    }, [[0, 0]], 0, summaryTableHeader);

    // Create error table
    createTable($("#errorsTable"), {"supportsControllersDiscrimination": false, "titles": ["Type of error", "Number of errors", "% in errors", "% in all samples"], "items": [{"data": ["502/Server Error", 414, 27.165354330708663, 0.138], "isController": false}, {"data": ["500/Server Error", 344, 22.57217847769029, 0.11466666666666667], "isController": false}, {"data": ["408/Server Error", 378, 24.80314960629921, 0.126], "isController": false}, {"data": ["504/Server Error", 388, 25.459317585301836, 0.12933333333333333], "isController": false}]}, function(index, item){
        switch(index){
            case 2:
            case 3:
                item = item.toFixed(2) + '%';
                break;
        }
        return item;
    }, [[1, 1]]);

        // Create top5 errors by sampler
    createTable($("#top5ErrorsBySamplerTable"), {"supportsControllersDiscrimination": false, "overall": {"data": ["Total", 300000, 1524, "502/Server Error", 414, "504/Server Error", 388, "408/Server Error", 378, "500/Server Error", 344, "", ""], "isController": false}, "titles": ["Sample", "#Samples", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors"], "items": [{"data": ["GET Room Messages 15min", 36022, 185, "502/Server Error", 56, "500/Server Error", 48, "504/Server Error", 45, "408/Server Error", 36, "", ""], "isController": false}, {"data": ["GET User History Repeated", 24323, 136, "502/Server Error", 39, "408/Server Error", 34, "504/Server Error", 32, "500/Server Error", 31, "", ""], "isController": false}, {"data": ["WS Open Connection", 9006, 44, "502/Server Error", 13, "500/Server Error", 13, "408/Server Error", 10, "504/Server Error", 8, "", ""], "isController": false}, {"data": ["GET User Messages 15min", 24059, 126, "500/Server Error", 37, "408/Server Error", 34, "504/Server Error", 30, "502/Server Error", 25, "", ""], "isController": false}, {"data": ["GET User Messages 5min", 36056, 176, "502/Server Error", 53, "504/Server Error", 49, "500/Server Error", 37, "408/Server Error", 37, "", ""], "isController": false}, {"data": ["WS JOIN", 11943, 62, "504/Server Error", 19, "502/Server Error", 17, "408/Server Error", 15, "500/Server Error", 11, "", ""], "isController": false}, {"data": ["GET User Rooms", 17880, 99, "408/Server Error", 27, "502/Server Error", 26, "504/Server Error", 26, "500/Server Error", 20, "", ""], "isController": false}, {"data": ["GET Active Users", 18106, 91, "502/Server Error", 27, "408/Server Error", 24, "504/Server Error", 22, "500/Server Error", 18, "", ""], "isController": false}, {"data": ["WS TEXT", 38806, 189, "502/Server Error", 56, "408/Server Error", 47, "504/Server Error", 44, "500/Server Error", 42, "", ""], "isController": false}, {"data": ["GET Room Messages 5min", 54105, 270, "408/Server Error", 77, "504/Server Error", 72, "502/Server Error", 67, "500/Server Error", 54, "", ""], "isController": false}, {"data": ["GET Room History Repeated", 29694, 146, "504/Server Error", 41, "408/Server Error", 37, "502/Server Error", 35, "500/Server Error", 33, "", ""], "isController": false}]}, function(index, item){
        return item;
    }, [[0, 0]], 0);

});
